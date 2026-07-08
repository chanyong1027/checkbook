package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * "풀이 동시 요청 1건 기준으로 사이징됨" 한계 재현 (측정 전 가설 1).
 * publicLibraryExecutor=20은 요청 1건의 fan-out 폭(20)과 정확히 일치하는 전역 공유 풀이라,
 * 동시 요청 2건이면 자식 40개가 20슬롯을 경합한다. 자식당 1500ms 지연 기준:
 * 첫 배치 20개만 fan-out 윈도(2200ms) 안에 도착, 나머지 20개는 3000ms에 완료되어 버려진다.
 */
@Tag("diagnosis") // 타이밍(sleep) 의존 재현 테스트 — 기본 스위트 제외, ./gradlew diagnosisTest로 실행
@Timeout(10) // 이 테스트가 잡으려는 회귀가 기아/데드락 계열 — 회귀 시 행 대신 실패로
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ConcurrentRequestContentionTest {

    private static final String TEST_ISBN = "9788936439743";
    private static final int LIBRARY_COUNT = 20;
    private static final int API_LATENCY_MS = 1500;

    @Mock
    private AladinBookService aladinBookService;
    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;
    @Mock
    private PublicLibraryRepository publicLibraryRepository;
    @Mock
    private MillieBookService millieBookService;

    @Test
    void 운영_사이즈_풀에서_동시_요청_2건이면_fanout_절반이_데드라인을_놓친다() throws Exception {
        int arrived = runTwoConcurrentSearches(
                Executors.newFixedThreadPool(3), Executors.newFixedThreadPool(20));

        // 자식 40건 중 첫 배치 20건만 윈도 내 도착.
        // 상한만 두면 0건 도착(재현 실패)도 통과하므로 정확히 20건을 단언한다 —
        // 첫 배치는 1500ms 완료로 윈도(2200ms) 안에 확실히 들어오고, 둘째 배치는 3000ms라 확실히 놓친다
        assertThat(arrived).isEqualTo(20);
    }

    @Test
    void 풀을_동시_요청_2건_기준으로_늘리면_40건_전부_도착한다() throws Exception {
        int arrived = runTwoConcurrentSearches(
                Executors.newFixedThreadPool(6), Executors.newFixedThreadPool(40));

        assertThat(arrived).isEqualTo(40);
    }

    private int runTwoConcurrentSearches(ExecutorService searchPool, ExecutorService libraryPool)
            throws Exception {
        setupMocks();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            PublicLibraryAvailabilityService availabilityService = new PublicLibraryAvailabilityService(
                    snapshotService, publicLibraryRepository, libraryPool);
            ReflectionTestUtils.setField(availabilityService, "pageSize", 20);
            ReflectionTestUtils.setField(availabilityService, "maxCount", 20);
            ReflectionTestUtils.setField(availabilityService, "fanoutTimeoutMs", 2200L);
            SearchService service = new SearchService(
                    aladinBookService, millieBookService, availabilityService, searchPool);

            Callable<SearchResponse> call = () -> service.search(TEST_ISBN, 37.5665, 126.9780);
            List<Future<SearchResponse>> futures = callers.invokeAll(List.of(call, call));

            int arrived = 0;
            for (Future<SearchResponse> future : futures) {
                arrived += future.get().publicLibraries().size();
            }
            return arrived;
        } finally {
            callers.shutdownNow();
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
