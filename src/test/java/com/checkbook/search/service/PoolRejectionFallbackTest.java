package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AbortPolicy의 동기 RejectedExecutionException 대응 검증.
 * supplyAsync는 풀 거절 시 예외를 동기로 던지므로(.exceptionally 체인이 붙기 전),
 * 거절 안전 헬퍼(submitSafely) 없이는 검색 전체가 500으로 죽는다.
 * 헬퍼 적용 후에는 해당 섹션만 FAILED로 강등되고 응답은 정상 반환되어야 한다.
 */
@Timeout(10)
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PoolRejectionFallbackTest {

    private static final String TEST_ISBN = "9788936439743";

    @Mock
    private AladinBookService aladinBookService;
    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;
    @Mock
    private PublicLibraryRepository publicLibraryRepository;
    @Mock
    private MillieBookService millieBookService;

    @Test
    void 풀_포화로_제출이_거절되어도_500이_아니라_섹션_FAILED로_응답한다() {
        when(aladinBookService.identify(any(InputNormalizer.NormalizedQuery.class)))
                .thenReturn(Optional.of(new AladinSearchResult(
                        TEST_ISBN, "혼자가 혼자에게", "성해나", "창비", null, 16800)));

        // abort 풀(스레드 1 + 큐 1)을 블로커로 포화 → 이후 모든 제출이 거절됨
        // (운영 구성과 동일한 형태를 직접 생성 — 관심사는 팩토리가 아니라 SearchService의 거절 대응)
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            saturated.execute(() -> {
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            saturated.execute(() -> { });

            PublicLibraryAvailabilityService availabilityService = new PublicLibraryAvailabilityService(
                    snapshotService, publicLibraryRepository, saturated);
            ReflectionTestUtils.setField(availabilityService, "pageSize", 20);
            ReflectionTestUtils.setField(availabilityService, "maxCount", 20);
            ReflectionTestUtils.setField(availabilityService, "fanoutTimeoutMs", 2200L);
            SearchService service = new SearchService(
                    aladinBookService, millieBookService, availabilityService, saturated);

            // 헬퍼가 없으면 여기서 RejectedExecutionException이 그대로 터진다
            SearchResponse response = service.search(TEST_ISBN, 37.5665, 126.9780);

            assertThat(response.publicLibraries()).isEmpty();

            // "목록만 빈" 회귀가 아니라 섹션 상태 자체가 FAILED임을 단언 (buildStatuses 상호작용 검증)
            Map<SearchSection, SearchSectionStatus> statuses = response.metadata().sectionStatuses().stream()
                    .collect(Collectors.toMap(
                            SearchResponse.SectionStatusDetail::section,
                            SearchResponse.SectionStatusDetail::status));
            assertThat(statuses.get(SearchSection.PUBLIC_LIBRARY)).isEqualTo(SearchSectionStatus.FAILED);
            assertThat(statuses.get(SearchSection.USED_BOOK)).isEqualTo(SearchSectionStatus.FAILED);
            assertThat(statuses.get(SearchSection.SUBSCRIPTION)).isEqualTo(SearchSectionStatus.FAILED);

            // 거절 사유는 TPE 원문이 아니라 정규화된 문구로 노출
            assertThat(response.metadata().failures())
                    .isNotEmpty()
                    .allSatisfy(f -> assertThat(f.reason()).isEqualTo("검색 풀 포화"));
        } finally {
            blocker.countDown();
            saturated.shutdownNow();
        }
    }

    @Test
    void 자식만_거절되면_해당_도서관만_스킵되고_섹션은_SUCCESS다() {
        when(aladinBookService.identify(any(InputNormalizer.NormalizedQuery.class)))
                .thenReturn(Optional.of(new AladinSearchResult(
                        TEST_ISBN, "혼자가 혼자에게", "성해나", "창비", null, 16800)));
        List<PublicLibrary> libraries = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> PublicLibrary.builder()
                        .libCode(String.format("LIB%03d", i)).name("테스트도서관" + i)
                        .lat(37.5665).lon(126.9780).build())
                .toList();
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libraries);

        ExecutorService healthyParents = Executors.newFixedThreadPool(3);
        // 자식 풀: 스레드 1(블로커 점유) + SynchronousQueue → 모든 fan-out 제출이 즉시 거절
        ThreadPoolExecutor saturatedChildren = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            saturatedChildren.execute(() -> {
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            PublicLibraryAvailabilityService availabilityService = new PublicLibraryAvailabilityService(
                    snapshotService, publicLibraryRepository, saturatedChildren);
            ReflectionTestUtils.setField(availabilityService, "pageSize", 20);
            ReflectionTestUtils.setField(availabilityService, "maxCount", 20);
            ReflectionTestUtils.setField(availabilityService, "fanoutTimeoutMs", 2200L);
            SearchService service = new SearchService(
                    aladinBookService, millieBookService, availabilityService, healthyParents);

            SearchResponse response = service.search(TEST_ISBN, 37.5665, 126.9780);

            // 자식 거절 = 도서관 개별 스킵 시맨틱: 목록은 비지만 섹션은 SUCCESS (부분 실패는
            // 측정에서 public_library_incomplete 지표가 담당), 요청은 500 없이 정상 응답
            assertThat(response.publicLibraries()).isEmpty();
            Map<SearchSection, SearchSectionStatus> statuses = response.metadata().sectionStatuses().stream()
                    .collect(Collectors.toMap(
                            SearchResponse.SectionStatusDetail::section,
                            SearchResponse.SectionStatusDetail::status));
            assertThat(statuses.get(SearchSection.PUBLIC_LIBRARY)).isEqualTo(SearchSectionStatus.SUCCESS);
        } finally {
            blocker.countDown();
            healthyParents.shutdownNow();
            saturatedChildren.shutdownNow();
        }
    }
}
