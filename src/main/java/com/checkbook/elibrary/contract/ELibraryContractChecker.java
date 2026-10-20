package com.checkbook.elibrary.contract;

import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ELibraryConcurrencyLimiter;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.elibrary.client.ELibraryClientException;
import com.checkbook.elibrary.client.KyoboELibClient;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.repository.ELibraryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 교보 전자도서관 주간 계약 확인(결정 notes/decisions/2026-10-21-전자도서관-주간계약확인-결정카드.md).
 * 교보 템플릿 하나를 1,700여 곳이 함께 쓰므로, 무작위로 고른 서로 다른 서버 3곳을 앱과 같은 클라이언트로 검색해 본다.
 * <ul>
 *   <li>2곳 이상 실패하면 다른 3곳을 더 본다. 그래도 2곳 이상이면 업체 공통 장애(템플릿 변경 등) — 코드를 고칠 일</li>
 *   <li>다시 봤더니 괜찮거나 처음부터 1곳만 실패했으면 그 사이트들이 고장 후보 — 사람이 정리(INACTIVE·주소 수정)</li>
 *   <li>robots가 막은 곳·하루 상한·동시 자리가 없는 곳은 세지 않고 다른 곳을 뽑는다(자동 수집은 robots를 따른다)</li>
 * </ul>
 * 결과는 지표(elibrary.contract.*)로 내보내고 Grafana 규칙이 Slack으로 알린다. 사이트 이름은 WARN 로그에 남긴다.
 */
@Slf4j
@Component
public class ELibraryContractChecker {

    /** 전수 조사와 같은 검색어 — 여러 도서관에 있을 만한 책이라 결과 목록 파싱까지 본다 */
    static final String KEYWORD = "어린왕자";
    static final int SITES_PER_BATCH = 3;
    static final int FAILURES_TO_RECHECK = 2;
    // robots_disallowed 값을 채우기 전에는 후보 대부분이 robots에 막혀 여러 번 뽑는다 — 한 번에 찔러 보는 곳의 상한
    static final int MAX_SITE_ATTEMPTS = 30;
    // 동시 자리를 기다리는 시간. 사용자 검색의 사이트 마감과 같다
    private static final long SLOT_WAIT_MS = 15_000;
    private static final List<String> RESULTS = List.of("pass", "site_failure", "vendor_failure", "undetermined");

    public enum Verdict {
        /** 결과 목록이나 빈 결과 화면을 읽었다 */
        OK,
        /** 구조 변경·파싱 실패·검색 주소 없음·예상 못 한 오류 — 업체가 바꿨으면 이렇게 된다 */
        TEMPLATE_SUSPECT,
        /** 시간 초과·접속 실패·HTTP 오류·robots에 닿지 않음 */
        UNREACHABLE
    }

    public record SiteCheck(ELibrary library, Verdict verdict, String detail) {

        boolean failed() {
            return verdict != Verdict.OK;
        }
    }

    public record Report(String result, List<SiteCheck> checks, List<SiteCheck> failedSites) {
    }

    private final ELibraryRepository repository;
    private final KyoboELibClient client;
    private final RobotsTxtFetcher robotsFetcher;
    private final ExternalCallBudget budget;
    private final ELibraryConcurrencyLimiter concurrencyLimiter;
    private final MeterRegistry meterRegistry;
    private final Consumer<List<ELibrary>> shuffle;
    private final Counter siteFailed;

    @Autowired
    public ELibraryContractChecker(ELibraryRepository repository, KyoboELibClient client, RobotsTxtFetcher robotsFetcher,
                                   ExternalCallBudget budget, ELibraryConcurrencyLimiter concurrencyLimiter,
                                   MeterRegistry meterRegistry) {
        this(repository, client, robotsFetcher, budget, concurrencyLimiter, meterRegistry, Collections::shuffle);
    }

    ELibraryContractChecker(ELibraryRepository repository, KyoboELibClient client, RobotsTxtFetcher robotsFetcher,
                            ExternalCallBudget budget, ELibraryConcurrencyLimiter concurrencyLimiter,
                            MeterRegistry meterRegistry, Consumer<List<ELibrary>> shuffle) {
        this.repository = repository;
        this.client = client;
        this.robotsFetcher = robotsFetcher;
        this.budget = budget;
        this.concurrencyLimiter = concurrencyLimiter;
        this.meterRegistry = meterRegistry;
        this.shuffle = shuffle;
        // 알림 규칙이 increase()로 본다 — 결과가 처음 나는 순간에 생기면 그 첫 사건을 못 센다
        RESULTS.forEach(this::resultCounter);
        this.siteFailed = Counter.builder("elibrary.contract.site.failed")
                .description("주간 계약 확인에서 고장 후보로 알린 전자도서관 사이트 수")
                .register(meterRegistry);
    }

    public Report run() {
        List<ELibrary> candidates = new ArrayList<>(repository
                .findAllByStatusAndVendorTypeAndLoginRequiredFalseAndRobotsDisallowedFalse(ELibraryStatus.ACTIVE, VendorType.KYOBO));
        shuffle.accept(candidates);
        Picker picker = new Picker(candidates.iterator());

        List<SiteCheck> first = picker.nextBatch();
        List<SiteCheck> checks = new ArrayList<>(first);
        List<SiteCheck> failed = new ArrayList<>(first.stream().filter(SiteCheck::failed).toList());
        Report report;
        if (first.size() < SITES_PER_BATCH) {
            report = undetermined(checks, picker);
        } else if (failed.size() < FAILURES_TO_RECHECK) {
            report = new Report(failed.isEmpty() ? "pass" : "site_failure", checks, failed);
        } else {
            // 우연히 고장 사이트를 뽑은 것인지, 업체가 바꿔 어디를 뽑아도 깨지는 것인지 가른다
            List<SiteCheck> second = picker.nextBatch();
            checks.addAll(second);
            List<SiteCheck> secondFailed = second.stream().filter(SiteCheck::failed).toList();
            if (second.size() < SITES_PER_BATCH) {
                report = undetermined(checks, picker);
            } else if (secondFailed.size() >= FAILURES_TO_RECHECK) {
                report = new Report("vendor_failure", checks, List.of());
            } else {
                failed.addAll(secondFailed);
                report = new Report("site_failure", checks, failed);
            }
        }
        record(report);
        return report;
    }

    private Report undetermined(List<SiteCheck> checks, Picker picker) {
        log.warn("전자도서관 계약 확인 판정 불가: 검사할 사이트를 {}곳씩 찾지 못함(본 곳 {}, robots 막힘 {}, 상한 {}, 남은 후보 {})",
                SITES_PER_BATCH, checks.size(), picker.robotsBlocked, picker.limited, picker.remaining.hasNext());
        return new Report("undetermined", checks, List.of());
    }

    private void record(Report report) {
        resultCounter(report.result()).increment();
        if (report.result().equals("vendor_failure")) {
            long template = report.checks().stream().filter(c -> c.verdict() == Verdict.TEMPLATE_SUSPECT).count();
            long unreachable = report.checks().stream().filter(c -> c.verdict() == Verdict.UNREACHABLE).count();
            log.warn("전자도서관 계약 확인 실패: 교보 공통 장애 의심 — {}곳 중 틀 변경 의심 {}, 닿지 않음 {}. 사이트: {}",
                    report.checks().size(), template, unreachable, describe(report.checks()));
        }
        for (SiteCheck failed : report.failedSites()) {
            siteFailed.increment();
            log.warn("전자도서관 사이트 고장 후보: id={}, name={}, base_url={}, 판정={}, 이유={}",
                    failed.library().getId(), failed.library().getName(), failed.library().getBaseUrl(),
                    failed.verdict(), failed.detail());
        }
        log.info("전자도서관 계약 확인 완료: result={}, 사이트: {}", report.result(), describe(report.checks()));
    }

    private static String describe(List<SiteCheck> checks) {
        return checks.stream().map(c -> c.library().getId() + ":" + c.verdict()).toList().toString();
    }

    private Counter resultCounter(String result) {
        return Counter.builder("elibrary.contract.check")
                .description("전자도서관 주간 계약 확인 결과")
                .tag("result", result)
                .register(meterRegistry);
    }

    /** 섞인 후보에서 서로 다른 서버를 하나씩 꺼내 확인한다. 건너뛴 곳은 세지 않는다 */
    private final class Picker {

        private final Iterator<ELibrary> remaining;
        private final Set<String> usedHosts = new HashSet<>();
        // 한 번 도는 동안 같은 출처의 robots는 한 번만 받는다
        private final Map<String, RobotsTxtFetcher.Result> robotsByOrigin = new HashMap<>();
        private int attempts;
        private int robotsBlocked;
        private int limited;

        Picker(Iterator<ELibrary> remaining) {
            this.remaining = remaining;
        }

        List<SiteCheck> nextBatch() {
            List<SiteCheck> batch = new ArrayList<>();
            while (batch.size() < SITES_PER_BATCH && attempts < MAX_SITE_ATTEMPTS && remaining.hasNext()) {
                ELibrary library = remaining.next();
                String host = hostOf(library.getBaseUrl());
                // 한 서버에 포트만 다른 사이트가 여럿이다 — 한 서버가 둘로 세지면 과반 판정이 기운다
                if (host == null || !usedHosts.add(host)) {
                    continue;
                }
                attempts++;
                SiteCheck check = check(library);
                if (check != null) {
                    log.info("전자도서관 계약 확인: id={}, name={}, 판정={}, 이유={}",
                            library.getId(), library.getName(), check.verdict(), check.detail());
                    batch.add(check);
                }
            }
            return batch;
        }

        /** 건너뛸 사이트면 null */
        private SiteCheck check(ELibrary library) {
            String robotsSkip = robotsVerdict(library);
            if (robotsSkip != null) {
                if (robotsSkip.isEmpty()) {
                    robotsBlocked++;
                    return null;
                }
                return new SiteCheck(library, Verdict.UNREACHABLE, robotsSkip);
            }
            // 자리 → 예산 순서(검색 서비스와 같다): 자리를 못 얻어 부르지 않은 곳이 하루 상한을 쓰지 않게
            if (!concurrencyLimiter.tryAcquire(library.getId(), SLOT_WAIT_MS)) {
                limited++;
                return null;
            }
            try {
                budget.acquireELibrary(library.getId(), library.isRobotsDisallowed());
            } catch (DailyBudgetExceededException e) {
                concurrencyLimiter.release(library.getId());
                limited++;
                return null;
            }
            try {
                client.search(library.getBaseUrl(), KEYWORD);
                return new SiteCheck(library, Verdict.OK, "");
            } catch (ELibraryClientException e) {
                return new SiteCheck(library, verdictOf(e.kind()), e.kind().name().toLowerCase(Locale.ROOT));
            } catch (RuntimeException e) {
                // 클라이언트가 분류하지 못한 예외는 처음 보는 화면에서 파서가 넘어진 경우가 대부분이다
                return new SiteCheck(library, Verdict.TEMPLATE_SUSPECT, "예상 못 한 오류: " + e);
            } finally {
                concurrencyLimiter.release(library.getId());
            }
        }

        /**
         * 앱이 부를 갈래 주소마다 그 출처의 robots로 본다(survey.py와 같은 규칙).
         * null = 검색해도 됨, "" = 건너뜀(막힘), 그 밖 = 닿지 않음(이유)
         */
        private String robotsVerdict(ELibrary library) {
            List<String> bases = KyoboELibClient.searchBases(library.getBaseUrl());
            for (int i = 0; i < bases.size(); i++) {
                URI url = URI.create(KyoboELibClient.searchUrl(bases.get(i), KEYWORD));
                String origin = url.getScheme() + "://" + url.getRawAuthority();
                RobotsTxtFetcher.Result robots = robotsByOrigin.computeIfAbsent(origin, robotsFetcher::fetch);
                if (!robots.reachable()) {
                    if (i == 0) {
                        return "robots에 닿지 않음: " + robots.detail();
                    }
                    // 뒤 갈래(http 주소에 붙여 보는 https)의 서버가 없으면 앱 요청도 닿지 않는다 — 막을 대상이 없다.
                    // 서버는 있는데 5xx면 RFC대로 막힌 것으로 본다
                    if (robots.serverError()) {
                        return "";
                    }
                    continue;
                }
                if (!robots.robots().allows(url.getRawPath() + "?" + url.getRawQuery())) {
                    return "";
                }
            }
            return null;
        }
    }

    private static Verdict verdictOf(ELibraryClientException.Kind kind) {
        return switch (kind) {
            case STRUCTURE_CHANGED, PARSE_FAILED, ENDPOINT_NOT_FOUND -> Verdict.TEMPLATE_SUSPECT;
            case HTTP_ERROR, TIMEOUT, CONNECTION_FAILED -> Verdict.UNREACHABLE;
        };
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
