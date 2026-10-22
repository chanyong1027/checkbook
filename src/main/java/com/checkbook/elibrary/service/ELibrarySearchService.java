package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ELibraryConcurrencyLimiter;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.elibrary.client.ELibClient;
import com.checkbook.elibrary.client.ELibClientResolver;
import com.checkbook.elibrary.client.ELibraryClientException;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.dto.ELibrarySearchStatus;
import com.checkbook.elibrary.repository.ELibraryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ELibrarySearchService {

    private final ELibraryRepository eLibraryRepository;
    private final ELibClientResolver eLibClientResolver;
    private final ExecutorService eLibraryExecutor;
    private final BookMatcher matcher;
    private final BookMetadataNormalizer normalizer;
    private final ExternalCallBudget budget;
    private final ELibraryConcurrencyLimiter concurrencyLimiter;
    private final MeterRegistry meterRegistry;

    /** elibrary.scrape의 outcome 값 전부(수집 결과 + 크롤러 실패 종류) */
    private static final List<String> SCRAPE_OUTCOMES = scrapeOutcomes();

    // 사이트 하나의 마감 — 작업이 실제로 시작한 때부터 잰다(큐 대기는 totalTimeoutMs가 묶는다). T4 결정 카드 11·11-1
    @Value("${elibrary.per-library-timeout:5000}")
    private long perLibraryTimeoutMs = 5_000;

    // 같은 사이트 동시 요청 자리를 기다리는 상한 — 사이트 마감 안에 수집할 시간(관측 최대 3.5초)을 남긴다. T4 결정 카드 12
    @Value("${elibrary.slot-wait-timeout:1000}")
    private long slotWaitTimeoutMs = 1_000;

    @Value("${elibrary.total-timeout:20000}")
    private long totalTimeoutMs = 20_000;

    public ELibrarySearchService(
            ELibraryRepository eLibraryRepository,
            ELibClientResolver eLibClientResolver,
            @Qualifier("eLibraryExecutor") ExecutorService eLibraryExecutor,
            BookMatcher matcher,
            BookMetadataNormalizer normalizer,
            ExternalCallBudget budget,
            ELibraryConcurrencyLimiter concurrencyLimiter,
            MeterRegistry meterRegistry
    ) {
        this.eLibraryRepository = eLibraryRepository;
        this.eLibClientResolver = eLibClientResolver;
        this.eLibraryExecutor = eLibraryExecutor;
        this.matcher = matcher;
        this.normalizer = normalizer;
        this.budget = budget;
        this.concurrencyLimiter = concurrencyLimiter;
        this.meterRegistry = meterRegistry;
        // 업체 × 결과를 기동 때 0으로 미리 만든다. 구조 변경처럼 드문 결과가 처음 나는 순간에 생기면
        // 첫 값이 1이라 increase()가 그 사건을 못 센다(업체 구조 변경 알림의 첫 사건을 놓침)
        for (VendorType vendor : VendorType.values()) {
            for (String outcome : SCRAPE_OUTCOMES) {
                scrapeTimer(vendor, outcome);
            }
        }
        slotWaitTimer("acquired");
        slotWaitTimer("timeout");
    }

    public ELibrarySearchResponse search(String title, String author, String libraryIds) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_KEYWORD);
        }
        List<Long> ids = parseLibraryIds(libraryIds);
        BookMatcher.Selected selected = new BookMatcher.Selected(title, author);

        long startAll = System.currentTimeMillis();

        List<ELibrary> foundLibraries = eLibraryRepository.findAllById(ids);
        Map<Long, ELibrary> foundMap = foundLibraries.stream()
                .collect(Collectors.toMap(ELibrary::getId, library -> library));

        List<ELibrarySearchResponse.ELibraryFailureDetail> failures = new ArrayList<>();
        List<Long> validIds = new ArrayList<>();

        for (Long id : ids) {
            ELibrary library = foundMap.get(id);
            if (library == null) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, null, "존재하지 않는 도서관"));
            } else if (library.getStatus() != ELibraryStatus.ACTIVE) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, library.getName(), "비활성화된 도서관"));
            } else if (library.isLoginRequired()) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, library.getName(), "로그인 필요 도서관"));
            } else {
                validIds.add(id);
            }
        }

        Map<Long, ScrapeTask> futureMap = new LinkedHashMap<>();
        // 응답(전체 마감)이 나가는 시각. 이 뒤에 큐에서 꺼낸 작업은 결과가 이미 TIMEOUT으로 나갔으니 사이트를 부르지 않는다
        long responseDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs);
        for (Long id : validIds) {
            ELibrary library = foundMap.get(id);
            long startedAt = System.currentTimeMillis();
            // 사이트 마감은 작업 안에서 지킨다(자리 대기 상한 + 수집기에 남은 시간). 제출 시각부터 재는 orTimeout을 두면
            // 큐에서 기다린 사이트가 시작하자마자 잘린다(T4 카드 11-1). 큐 대기까지 포함한 상한은 아래 전체 마감
            futureMap.put(id, new ScrapeTask(
                    submitSafely(() -> searchWithinLimits(library, selected, responseDeadlineNanos)),
                    startedAt
            ));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futureMap.values().stream()
                        .map(ScrapeTask::future)
                        .toArray(CompletableFuture[]::new)
        );

        try {
            all.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("전자도서관 전체 타임아웃 {}ms", totalTimeoutMs);
        } catch (Exception e) {
            log.warn("전자도서관 검색 대기 중 오류", e);
        }

        List<ELibrarySearchResponse.ELibraryResult> results = new ArrayList<>();
        for (Long id : validIds) {
            ELibrary library = foundMap.get(id);
            results.add(toResult(library, futureMap.get(id)));
        }

        long totalElapsed = System.currentTimeMillis() - startAll;
        return new ELibrarySearchResponse(
                results,
                new ELibrarySearchResponse.ELibrarySearchMetadata(
                        totalElapsed,
                        LocalDateTime.now(),
                        failures
                )
        );
    }

    /**
     * eLibraryExecutor가 bounded(AbortPolicy)로 전환되어, 풀 거절 시 supplyAsync가 동기로 던지는
     * RejectedExecutionException을 failedFuture로 흡수한다 — 없으면 제출 루프 밖으로 전파돼 요청 전체가 500.
     * 하류 toResult()가 CompletionException을 FAILED로 분류하므로 해당 도서관만 실패 처리된다.
     */
    private CompletableFuture<ScrapeOutcome> submitSafely(Supplier<ScrapeOutcome> task) {
        try {
            return CompletableFuture.supplyAsync(task, eLibraryExecutor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 사이트당 동시 요청 자리 → 하루 예산 → 수집 순서로 돈다.
     * 예산은 작업이 실제로 돌 때, 그리고 자리를 얻은 뒤에 차감한다 — 풀 포화로 거절되거나 자리를 못 얻어 수집을 안 했는데도
     * 하루 예산이 줄면 자정까지 막힐 수 있다. 소진이면 toResult가 DAILY_LIMIT으로 바꾼다.
     * 사이트 마감은 작업이 실제로 시작한 때부터 잰다. 자리는 상한(slotWaitTimeoutMs)까지만 기다린다 — 마감 끝까지 기다리면
     * 자리를 얻어도 수집할 시간이 없고 그동안 풀 스레드를 쥔다(T4 카드 12). 못 얻으면 시간 초과로 본다.
     * 사이트 마감은 응답 마감을 넘지 않는다 — 큐에서 오래 기다려 응답이 이미 나간 뒤라면 자리·예산을 잡기 전에 끝낸다
     * (안 그러면 결과는 TIMEOUT으로 나갔는데 사이트에 부하만 주고 하루 예산을 깎는다, 1차 리뷰 1)
     */
    private ScrapeOutcome searchWithinLimits(ELibrary library, BookMatcher.Selected selected, long responseDeadlineNanos) {
        // 마감은 단조 시계로 잰다(벽시계는 NTP 보정으로 앞뒤로 움직인다)
        long startedNanos = System.nanoTime();
        if (startedNanos - responseDeadlineNanos >= 0) {
            throw timedOut(library, "응답이 이미 나간 뒤에 시작해 수집하지 않음");
        }
        long deadlineNanos = Math.min(startedNanos + TimeUnit.MILLISECONDS.toNanos(perLibraryTimeoutMs),
                responseDeadlineNanos);
        long waitStarted = System.nanoTime();
        boolean acquired = concurrencyLimiter.tryAcquire(library.getId(),
                Math.min(slotWaitTimeoutMs, remainingMs(deadlineNanos)));
        slotWaitTimer(acquired ? "acquired" : "timeout").record(System.nanoTime() - waitStarted, TimeUnit.NANOSECONDS);
        if (!acquired) {
            throw timedOut(library, "동시 요청 자리를 대기 상한 안에 못 얻음");
        }
        try {
            // 풀 큐나 자리 대기에서 마감을 넘겼다 — 응답은 이미 시간 초과로 나가 사이트에 보내 봐야 버려진다
            if (remainingMs(deadlineNanos) <= 0) {
                throw timedOut(library, "마감이 지나 수집하지 않음");
            }
            budget.acquireELibrary(library.getId(), library.isRobotsDisallowed());
            return searchBooks(library, selected, deadlineNanos);
        } finally {
            concurrencyLimiter.release(library.getId());
        }
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    // CompletionException으로 감싸 던지면 supplyAsync가 그대로 쓴다 — toResult가 원인 TimeoutException을 보고 TIMEOUT으로 바꾼다
    private CompletionException timedOut(ELibrary library, String reason) {
        log.info("전자도서관 {}: id={}, name={}", reason, library.getId(), library.getName());
        return new CompletionException(new TimeoutException(reason));
    }

    private ScrapeOutcome searchBooks(ELibrary library, BookMatcher.Selected selected, long deadlineNanos) {
        long start = System.currentTimeMillis();
        ELibClient client = eLibClientResolver.resolve(library.getVendorType());
        List<ELibrarySearchResponse.ELibraryBook> raw = client.search(
                library.getBaseUrl(),
                selected.title(),
                deadlineNanos
        );

        List<ELibrarySearchResponse.ELibraryBook> kept = new ArrayList<>();
        Map<BookMatcher.MatchPath, Integer> pathCounts = new EnumMap<>(BookMatcher.MatchPath.class);
        for (ELibrarySearchResponse.ELibraryBook book : raw) {
            BookMatcher.MatchResult r = matcher.match(book.title(), book.author(), selected);
            if (r.matched()) {
                kept.add(book);
                pathCounts.merge(r.path(), 1, Integer::sum);
            }
        }

        logFilterSignal(library, selected, raw, kept, pathCounts);

        return new ScrapeOutcome(kept, System.currentTimeMillis() - start);
    }

    private void logFilterSignal(
            ELibrary library,
            BookMatcher.Selected selected,
            List<ELibrarySearchResponse.ELibraryBook> raw,
            List<ELibrarySearchResponse.ELibraryBook> kept,
            Map<BookMatcher.MatchPath, Integer> pathCounts
    ) {
        String caseType;
        if (!raw.isEmpty() && kept.isEmpty()) {
            caseType = "filtered_to_zero";
        } else if (kept.size() >= 2) {
            caseType = "multiple_kept";
        } else if (kept.size() == 1
                && pathCounts.getOrDefault(BookMatcher.MatchPath.TITLE_ONLY, 0) > 0) {
            caseType = "title_only_pass";
        } else {
            return; // normal single-pass — don't log
        }

        List<String> rawTitles = caseType.equals("filtered_to_zero")
                ? raw.stream().map(ELibrarySearchResponse.ELibraryBook::title).limit(10).toList()
                : List.of();
        List<String> keptTitles = kept.stream()
                .map(ELibrarySearchResponse.ELibraryBook::title).limit(10).toList();

        String pathCountsStr = pathCounts.entrySet().stream()
                .map(e -> e.getKey().name() + ":" + e.getValue())
                .collect(Collectors.joining(","));

        log.info("elibraryFilterSignal case={} libraryId={} libraryName={} vendor={} "
                        + "selectedTitle={} selectedAuthor={} vendorQuery={} "
                        + "rawCount={} keptCount={} rawTitles={} keptTitles={} matchPathCounts={} "
                        + "normSelectedTitle={} normSelectedPrefix={} selAuthorTokens={}",
                caseType, library.getId(), library.getName(), library.getVendorType(),
                selected.title(), selected.author(), selected.title(),
                raw.size(), kept.size(), rawTitles, keptTitles, pathCountsStr,
                normalizer.debugNormTitle(selected.title()),
                normalizer.debugNormTitlePrefix(selected.title()),
                normalizer.debugAuthorTokens(selected.author()));
    }

    private ELibrarySearchResponse.ELibraryResult toResult(
            ELibrary library,
            ScrapeTask scrapeTask
    ) {
        CompletableFuture<ScrapeOutcome> future = scrapeTask.future();
        if (!future.isDone()) {
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
            recordScrape(library, "timeout", elapsedMs);
            return timedOutResult(library, elapsedMs);
        }

        try {
            ScrapeOutcome outcome = future.join();
            recordScrape(library, outcome.books().isEmpty() ? "empty" : "found", outcome.elapsedMs());
            return new ELibrarySearchResponse.ELibraryResult(
                    library.getId(),
                    library.getName(),
                    library.getVendorType().name(),
                    outcome.books(),
                    ELibrarySearchStatus.SUCCESS,
                    outcome.elapsedMs()
            );
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof DailyBudgetExceededException) {
                recordScrape(library, "daily_limit", 0);
                return new ELibrarySearchResponse.ELibraryResult(
                        library.getId(), library.getName(), library.getVendorType().name(),
                        List.of(), ELibrarySearchStatus.DAILY_LIMIT, 0);
            }
            // 수집기가 사이트 마감에 걸린 것도 사용자에게는 같은 TIMEOUT이다(전에는 대부분 orTimeout이 먼저 걸려 TIMEOUT이었다)
            if (cause instanceof TimeoutException
                    || cause instanceof ELibraryClientException e && e.kind() == ELibraryClientException.Kind.TIMEOUT) {
                long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
                recordScrape(library, "timeout", elapsedMs);
                return timedOutResult(library, elapsedMs);
            }

            // 지표는 업체 단위라 어느 사이트인지는 이 로그로 본다(같은 사이트 반복인지, 여러 사이트인지)
            String outcome = failureOutcome(cause);
            log.warn("전자도서관 검색 실패: id={}, name={}, outcome={}", library.getId(), library.getName(), outcome, cause);
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
            recordScrape(library, outcome, elapsedMs);
            return new ELibrarySearchResponse.ELibraryResult(
                    library.getId(),
                    library.getName(),
                    library.getVendorType().name(),
                    List.of(),
                    ELibrarySearchStatus.FAILED,
                    elapsedMs
            );
        }
    }

    /**
     * 수집 한 번의 결과와 시간. 태그는 업체 × 결과뿐이다 — 사이트(1,964곳)를 태그에 넣으면 시리즈가 폭증한다.
     * 업체 템플릿이 바뀌면 한 업체의 structure_changed·parse_failed·endpoint_not_found가 여러 사이트에서 한꺼번에 늘어난다.
     * 걸린 시간: timeout·실패는 제출 시점부터(큐·자리 대기 포함 — 사이트 탓만은 아니다), found·empty는 수집 시작부터,
     * daily_limit은 0. 자리 대기는 elibrary.slot.wait, 그 밖은 INFO 로그로 본다
     */
    private void recordScrape(ELibrary library, String outcome, long elapsedMs) {
        scrapeTimer(library.getVendorType(), outcome).record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    private static List<String> scrapeOutcomes() {
        LinkedHashSet<String> outcomes = new LinkedHashSet<>(
                List.of("found", "empty", "timeout", "daily_limit", "rejected", "error"));
        for (ELibraryClientException.Kind kind : ELibraryClientException.Kind.values()) {
            outcomes.add(kind.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(outcomes);
    }

    /** 같은 사이트 동시 요청 자리를 기다린 시간과 결과(acquired·timeout). 경쟁이 드물다는 전제(카드 12)를 운영에서 확인한다 */
    private Timer slotWaitTimer(String result) {
        return Timer.builder("elibrary.slot.wait")
                .description("전자도서관 사이트 동시 요청 자리를 기다린 시간")
                .tag("result", result)
                .register(meterRegistry);
    }

    private Timer scrapeTimer(VendorType vendor, String outcome) {
        return Timer.builder("elibrary.scrape")
                .description("전자도서관 사이트 수집 한 번의 결과와 걸린 시간")
                .tag("vendor", vendor.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static String failureOutcome(Throwable cause) {
        if (cause instanceof ELibraryClientException e) {
            return e.kind().name().toLowerCase(Locale.ROOT);
        }
        // 우리 풀이 가득 차 수집을 못 함 — 사이트 탓이 아니다
        return cause instanceof RejectedExecutionException ? "rejected" : "error";
    }

    private ELibrarySearchResponse.ELibraryResult timedOutResult(ELibrary library, long elapsedMs) {
        return new ELibrarySearchResponse.ELibraryResult(
                library.getId(),
                library.getName(),
                library.getVendorType().name(),
                List.of(),
                ELibrarySearchStatus.TIMEOUT,
                elapsedMs
        );
    }

    private List<Long> parseLibraryIds(String libraryIds) {
        if (libraryIds == null || libraryIds.isBlank()) {
            throw new BusinessException(ErrorCode.LIBRARY_IDS_REQUIRED);
        }

        String[] parts = libraryIds.split(",");
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) {
                    throw new BusinessException(ErrorCode.INVALID_LIBRARY_ID);
                }
                uniqueIds.add(id);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.INVALID_LIBRARY_ID);
            }
        }

        if (uniqueIds.size() > 20) {
            throw new BusinessException(ErrorCode.LIBRARY_IDS_LIMIT_EXCEEDED);
        }

        return List.copyOf(uniqueIds);
    }

    private record ScrapeOutcome(
            List<ELibrarySearchResponse.ELibraryBook> books,
            long elapsedMs
    ) {
    }

    private record ScrapeTask(
            CompletableFuture<ScrapeOutcome> future,
            long startedAt
    ) {
    }
}
