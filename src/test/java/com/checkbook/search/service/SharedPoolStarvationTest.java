package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.SearchResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 풀 분리 설계의 근거 재현 (측정 전 가설 4).
 * fetchPublicLibraries(부모)가 fan-out 자식 20개를 제출하고 블로킹 대기하는 중첩 구조에서,
 * searchExecutor와 publicLibraryExecutor를 같은 풀로 합치면 부모가 자식의 스레드를 점유해
 * 자식이 큐에 갇힌다(thread starvation). 타임아웃이 있어 영구 데드락 대신
 * "공공도서관 섹션 전멸 + fan-out 타임아웃 2.2초 소진"으로 발현된다.
 *
 * 기아 여부가 스케줄링 타이밍에 따라 스텁 사용량을 바꾸므로 LENIENT 사용.
 */
@Tag("diagnosis") // 타이밍(sleep) 의존 재현 테스트 — 기본 스위트 제외, ./gradlew diagnosisTest로 실행
@Timeout(10) // 기아/데드락 계열 재현 테스트 — 회귀 시 빌드 행 대신 실패로
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class SharedPoolStarvationTest {

    private static final String TEST_ISBN = "9788936439743";
    private static final int LIBRARY_COUNT = 20;
    private static final int API_LATENCY_MS = 10;

    @Mock
    private AladinBookService aladinBookService;
    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;
    @Mock
    private PublicLibraryRepository publicLibraryRepository;
    @Mock
    private MillieBookService millieBookService;

    @Test
    void 공유_풀에서는_fanout_자식이_기아상태가_되어_섹션이_빈_채로_돌아온다() {
        setupMocks();
        ExecutorService sharedPool = Executors.newFixedThreadPool(1);
        try {
            SearchService service = new SearchService(
                    aladinBookService, snapshotService, publicLibraryRepository,
                    millieBookService, sharedPool, sharedPool);

            long start = System.currentTimeMillis();
            SearchResponse response = service.search(TEST_ISBN, 37.5665, 126.9780);
            long elapsedMs = System.currentTimeMillis() - start;

            // 자식 태스크가 부모 뒤에 큐잉되어 한 건도 완료되지 못함
            assertThat(response.publicLibraries()).isEmpty();
            // API 지연이 10ms뿐인데도 fan-out 타임아웃 2200ms를 고스란히 소진
            assertThat(elapsedMs).isGreaterThanOrEqualTo(2200);
        } finally {
            sharedPool.shutdownNow();
        }
    }

    @Test
    void 풀을_분리하면_동일_조건에서_20곳이_전부_수집된다() {
        setupMocks();
        ExecutorService searchPool = Executors.newFixedThreadPool(3);
        ExecutorService libraryPool = Executors.newFixedThreadPool(20);
        try {
            SearchService service = new SearchService(
                    aladinBookService, snapshotService, publicLibraryRepository,
                    millieBookService, searchPool, libraryPool);

            long start = System.currentTimeMillis();
            SearchResponse response = service.search(TEST_ISBN, 37.5665, 126.9780);
            long elapsedMs = System.currentTimeMillis() - start;

            assertThat(response.publicLibraries()).hasSize(LIBRARY_COUNT);
            assertThat(elapsedMs).isLessThan(2200);
        } finally {
            searchPool.shutdownNow();
            libraryPool.shutdownNow();
        }
    }

    private void setupMocks() {
        when(aladinBookService.identify(any(InputNormalizer.NormalizedQuery.class)))
                .thenReturn(Optional.of(new AladinSearchResult(
                        TEST_ISBN, "혼자가 혼자에게", "성해나", "창비", null, 16800)));
        when(aladinBookService.getUsedBooks(TEST_ISBN))
                .thenReturn(new AladinUsedBookResult(5000, 6000, 7000, "url1", "url2", "url3"));
        when(millieBookService.findAvailability(any()))
                .thenReturn(MillieAvailability.unavailable());

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
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libraries);

        when(snapshotService.getAvailability(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(API_LATENCY_MS);
                    return new LibraryAvailabilityResult(
                            invocation.getArgument(1), true, true, SnapshotSourceStatus.SUCCESS);
                });
    }
}
