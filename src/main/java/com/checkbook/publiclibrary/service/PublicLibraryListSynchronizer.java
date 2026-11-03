package com.checkbook.publiclibrary.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchPage;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 우리 DB의 공공도서관 목록을 정보나루 참여 목록(libSrch)에 맞춘다.
 * <p>
 * 초기 적재 뒤로 목록을 다시 맞추지 않아, 정보나루에서 빠진 도서관(예: 2026-11 양평군 6곳)이 주변 후보로 남아
 * bookExist가 빈 응답을 주고 늘 '확인 못 함'이 됐다. 새로 참여한 도서관은 반대로 아예 묻지 않았다.
 * <p>
 * 정보나루 쪽 오류로 전국 도서관이 한꺼번에 빠지지 않게 두 가지를 먼저 본다. 둘 중 하나라도 걸리면 아무것도 바꾸지 않는다.
 * <ul>
 *   <li>받은 고유 도서관 코드 수가 numFound와 다르면 일부만 받은 것(INCOMPLETE) — 코드 없는 항목이나
 *       쪽 사이 중복이 섞여 개수만 맞는 경우도 여기서 걸린다</li>
 *   <li>빠질 도서관이 지금 활성 도서관의 maxRemovalRatio를 넘으면 의심스러운 축소(TOO_MANY_REMOVED)</li>
 * </ul>
 */
@Slf4j
@Service
public class PublicLibraryListSynchronizer {

    private static final int DEFAULT_PAGE_SIZE = 100;
    /** 쪽 수 상한. 지금 약 1,600곳 = 17쪽이라 넉넉하다. numFound가 엉뚱하게 커도 끝없이 돌지 않게 */
    private static final int MAX_PAGES = 100;

    public enum Result {
        SUCCESS, INCOMPLETE, TOO_MANY_REMOVED, FAILED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final DatanaruClient client;
    private final PublicLibraryRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final double maxRemovalRatio;
    private final int pageSize;
    private final Map<Result, Counter> counters = new EnumMap<>(Result.class);

    @Autowired
    public PublicLibraryListSynchronizer(DatanaruClient client, PublicLibraryRepository repository,
                                         TransactionTemplate transactionTemplate, MeterRegistry meterRegistry,
                                         @Value("${public-library.list-sync.max-removal-ratio:0.10}") double maxRemovalRatio) {
        this(client, repository, transactionTemplate, meterRegistry, maxRemovalRatio, DEFAULT_PAGE_SIZE);
    }

    PublicLibraryListSynchronizer(DatanaruClient client, PublicLibraryRepository repository,
                                  TransactionTemplate transactionTemplate, MeterRegistry meterRegistry,
                                  double maxRemovalRatio, int pageSize) {
        this.client = client;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.maxRemovalRatio = maxRemovalRatio;
        this.pageSize = pageSize;
        // 결과마다 미리 0으로 만든다 — 알림 규칙이 '지표 사라짐'과 '8일 넘게 성공 없음'을 판단하려면 기동 때부터 있어야 한다
        for (Result result : Result.values()) {
            counters.put(result, Counter.builder("public.library.sync")
                    .description("공공도서관 목록 동기화(정보나루 libSrch) 결과")
                    .tag("result", result.tag())
                    .register(meterRegistry));
        }
    }

    public Result sync() {
        Result result;
        try {
            Map<String, DatanaruLibSrchResult> fetched = fetchAll();
            result = fetched == null ? Result.INCOMPLETE : apply(fetched);
        } catch (RuntimeException e) {
            log.warn("공공도서관 목록 동기화 실패 — 이번엔 바꾸지 않음", e);
            result = Result.FAILED;
        }
        counters.get(result).increment();
        return result;
    }

    /** 전부 받았으면 코드별 목록, numFound와 맞지 않으면 null. 호출 실패는 예외로 올린다(DB 트랜잭션 밖에서 부른다). */
    private Map<String, DatanaruLibSrchResult> fetchAll() {
        List<DatanaruLibSrchResult> all = new ArrayList<>();
        Integer numFound = null;
        int received = 0;
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            DatanaruLibSrchPage page = client.libSrchPage(pageNo, pageSize);
            if (pageNo == 1) {
                numFound = page.numFound();
            }
            received += page.receivedCount();
            all.addAll(page.libraries());
            if (numFound == null || page.receivedCount() < pageSize || received >= numFound) {
                break;
            }
        }
        // 같은 코드가 두 번 오면 뒤의 값을 쓴다
        Map<String, DatanaruLibSrchResult> byCode = all.stream()
                .collect(Collectors.toMap(DatanaruLibSrchResult::libCode, Function.identity(), (a, b) -> b,
                        LinkedHashMap::new));
        if (numFound == null || received != numFound || byCode.size() != numFound) {
            log.warn("공공도서관 목록 동기화 중단 — 일부만 받음: numFound={}, received={}, 고유 코드={}",
                    numFound, received, byCode.size());
            return null;
        }
        return byCode;
    }

    private Result apply(Map<String, DatanaruLibSrchResult> byCode) {
        return transactionTemplate.execute(status -> {
            List<PublicLibrary> existing = repository.findAll();
            Map<String, PublicLibrary> existingByCode = existing.stream()
                    .collect(Collectors.toMap(PublicLibrary::getLibCode, Function.identity()));
            long activeCount = existing.stream().filter(PublicLibrary::isActive).count();
            List<PublicLibrary> removed = existing.stream()
                    .filter(PublicLibrary::isActive)
                    .filter(library -> !byCode.containsKey(library.getLibCode()))
                    .toList();
            if (activeCount > 0 && removed.size() > activeCount * maxRemovalRatio) {
                log.warn("공공도서관 목록 동기화 중단 — 빠질 도서관이 너무 많음: removed={}, active={}, 상한 비율={}",
                        removed.size(), activeCount, maxRemovalRatio);
                return Result.TOO_MANY_REMOVED;
            }

            List<PublicLibrary> added = new ArrayList<>();
            List<String> reactivated = new ArrayList<>();
            for (DatanaruLibSrchResult raw : byCode.values()) {
                DatanaruLibSrchResult r = fitColumns(raw);
                PublicLibrary library = existingByCode.get(r.libCode());
                if (library != null) {
                    if (!library.isActive()) {
                        reactivated.add(r.libCode());
                    }
                    library.refresh(r.name(), r.address(), r.lat(), r.lon(), r.regionName(), r.homepage(),
                            r.phone(), r.fax(), r.operatingHours(), r.closedDays());
                } else if (r.libCode().length() <= LIB_CODE_MAX && r.name() != null && !r.name().isBlank()
                        && r.lat() != null && r.lon() != null) {
                    added.add(PublicLibrary.builder()
                            .libCode(r.libCode()).name(r.name()).address(r.address())
                            .lat(r.lat()).lon(r.lon()).regionName(r.regionName()).homepage(r.homepage())
                            .phone(r.phone()).fax(r.fax())
                            .operatingHours(r.operatingHours()).closedDays(r.closedDays())
                            .build());
                }
            }
            removed.forEach(PublicLibrary::deactivate);
            repository.saveAll(added);

            log.info("공공도서관 목록 동기화 완료: 받음={}, 추가={}, 되살림={}, 비활성={}",
                    byCode.size(), added.size(), reactivated.size(), removed.size());
            if (!added.isEmpty()) {
                log.info("공공도서관 추가: {}", added.stream().map(l -> l.getLibCode() + " " + l.getName()).toList());
            }
            if (!reactivated.isEmpty()) {
                log.info("공공도서관 되살림: {}", reactivated);
            }
            if (!removed.isEmpty()) {
                log.info("공공도서관 비활성(정보나루 목록에서 빠짐): {}",
                        removed.stream().map(l -> l.getLibCode() + " " + l.getName()).toList());
            }
            return Result.SUCCESS;
        });
    }

    // public_library 열 길이(V1). 넘치는 값 하나 때문에 트랜잭션 전체가 롤백돼 매주 실패하지 않게 잘라 넣는다.
    // 코드가 너무 길면 넣을 수 없어 새로 넣지 않는다(받은 목록에는 있으니 비활성 대상도 아니다)
    private static final int LIB_CODE_MAX = 20;

    private static DatanaruLibSrchResult fitColumns(DatanaruLibSrchResult r) {
        return new DatanaruLibSrchResult(r.libCode(), fit(r.name(), 200), fit(r.address(), 500), r.lat(), r.lon(),
                fit(r.regionName(), 100), fit(r.homepage(), 500), fit(r.phone(), 200), fit(r.fax(), 200),
                fit(r.operatingHours(), 1000), fit(r.closedDays(), 1000));
    }

    private static String fit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
