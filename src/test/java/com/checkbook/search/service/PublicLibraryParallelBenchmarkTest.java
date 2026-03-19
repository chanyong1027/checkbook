package com.checkbook.search.service;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import com.checkbook.search.dto.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 공공도서관 fan-out: 순차 처리 vs 병렬 처리 응답시간 비교 벤치마크
 *
 * 목적: 이력서 수치 측정
 * 조건: 로컬 환경, 단일 요청, API 응답시간 100ms 시뮬레이션, 도서관 20곳
 */
@ExtendWith(MockitoExtension.class)
class PublicLibraryParallelBenchmarkTest {

    // 실제 도서관정보나루 API 평균 응답시간 시뮬레이션 (ms)
    private static final int SIMULATED_API_LATENCY_MS = 100;
    private static final int LIBRARY_COUNT = 20;
    private static final String TEST_ISBN = "9788936439743";

    @Mock
    private AladinClient aladinClient;

    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;

    @Mock
    private PublicLibraryRepository publicLibraryRepository;

    @Test
    void benchmark_sequential_vs_parallel() throws InterruptedException {
        // given
        setupMocks();

        // 워밍업 (JIT 컴파일 효과 제거)
        runSearch(Executors.newFixedThreadPool(LIBRARY_COUNT));
        Thread.sleep(200);

        // 순차 처리 측정 (1 thread = 순차 근사)
        long sequentialMs = measureAverageMs(Executors.newSingleThreadExecutor(), 3);

        Thread.sleep(500);

        // 병렬 처리 측정 (20 threads)
        long parallelMs = measureAverageMs(Executors.newFixedThreadPool(LIBRARY_COUNT), 3);

        double ratio = (double) sequentialMs / parallelMs;

        System.out.println("========================================");
        System.out.println("  공공도서관 fan-out 병렬 처리 벤치마크");
        System.out.println("  조건: 도서관 " + LIBRARY_COUNT + "곳, API 레이턴시 " + SIMULATED_API_LATENCY_MS + "ms 시뮬레이션");
        System.out.println("  측정: 3회 평균");
        System.out.println("----------------------------------------");
        System.out.printf("  순차 처리 (1 thread):  %4d ms%n", sequentialMs);
        System.out.printf("  병렬 처리 (%2d threads): %4d ms%n", LIBRARY_COUNT, parallelMs);
        System.out.printf("  단축 비율:             %.1f배%n", ratio);
        System.out.println("========================================");
        System.out.println();
        System.out.println("이력서 문장 예시:");
        System.out.printf("  공공도서관 소장 조회 %d곳 fan-out을 CompletableFuture로 병렬 실행,%n", LIBRARY_COUNT);
        System.out.printf("  순차 처리(%dms) 대비 응답시간 %.1f배 단축 → %dms%n",
                sequentialMs, ratio, parallelMs);
        System.out.println("  (로컬 환경, API 레이턴시 " + SIMULATED_API_LATENCY_MS + "ms 기준)");
    }

    private long measureAverageMs(ExecutorService executor, int runs) {
        long total = 0;
        for (int i = 0; i < runs; i++) {
            long start = System.currentTimeMillis();
            runSearch(executor);
            total += System.currentTimeMillis() - start;
        }
        executor.shutdownNow();
        return total / runs;
    }

    private void runSearch(ExecutorService publicLibraryExecutor) {
        ExecutorService searchExecutor = Executors.newFixedThreadPool(3);
        try {
            SearchService service = new SearchService(
                    aladinClient,
                    snapshotService,
                    publicLibraryRepository,
                    searchExecutor,
                    publicLibraryExecutor
            );
            service.search("혼자가 혼자에게", 37.5665, 126.9780);
        } finally {
            searchExecutor.shutdownNow();
        }
    }

    private void setupMocks() {
        // 알라딘 도서 식별
        when(aladinClient.searchBook(anyString()))
                .thenReturn(Optional.of(new AladinSearchResult(
                        TEST_ISBN, "혼자가 혼자에게", "성해나", "창비", null, 16800)));

        // 중고가격
        when(aladinClient.getUsedBooks(TEST_ISBN))
                .thenReturn(new AladinUsedBookResult(5000, 6000, 7000, "url1", "url2", "url3"));

        // 도서관 20곳 목록
        List<PublicLibrary> libraries = IntStream.rangeClosed(1, LIBRARY_COUNT)
                .mapToObj(i -> PublicLibrary.builder()
                        .libCode(String.format("LIB%03d", i))
                        .name("테스트도서관" + i)
                        .address("서울시 테스트구 " + i + "동")
                        .lat(37.5665 + i * 0.001)
                        .lon(126.9780 + i * 0.001)
                        .homepage("https://lib" + i + ".example.com")
                        .build())
                .toList();

        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20))
                .thenReturn(libraries);

        // 각 도서관 API 호출에 100ms 지연 시뮬레이션
        when(snapshotService.getAvailability(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(SIMULATED_API_LATENCY_MS);
                    return new LibraryAvailabilityResult(invocation.getArgument(1), true, true, SnapshotSourceStatus.SUCCESS);
                });
    }
}
