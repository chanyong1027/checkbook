package com.checkbook.publiclibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.BatchStopReason;
import com.checkbook.publiclibrary.dto.LibraryCheckFailure;
import com.checkbook.publiclibrary.dto.LibraryState;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchItem;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchResponse;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicLibraryBatchServiceTest {

    private static final String ISBN = "9788936439743";
    private static final double LAT = 37.5665;
    private static final double LON = 126.9780;

    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;
    @Mock
    private PublicLibraryRepository repository;

    private ExecutorService executor;
    private PublicLibraryBatchService service;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(10);
        service = newService(executor, 2200L);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private PublicLibraryBatchService newService(ExecutorService pool, long timeoutMs) {
        PublicLibraryBatchService created = new PublicLibraryBatchService(snapshotService, repository, pool);
        ReflectionTestUtils.setField(created, "pageSize", 5);
        ReflectionTestUtils.setField(created, "radiusKm", 10.0);
        ReflectionTestUtils.setField(created, "fanoutTimeoutMs", timeoutMs);
        return created;
    }

    private static PublicLibrary lib(int i) {
        return PublicLibrary.builder()
                .libCode(String.format("L%03d", i))
                .name("도서관" + i)
                .address("서울 " + i)
                .lat(LAT + i * 0.001)
                .lon(LON)
                .homepage("https://lib" + i + ".example")
                .build();
    }

    private static List<PublicLibrary> libs(int count) {
        List<PublicLibrary> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(lib(i));
        }
        return list;
    }

    private void stubState(boolean hasBook, boolean loanAvailable) {
        when(snapshotService.getAvailability(eq(ISBN), anyString()))
                .thenAnswer(inv -> new LibraryAvailabilityResult(
                        inv.getArgument(1), hasBook, loanAvailable, SnapshotSourceStatus.SUCCESS));
    }

    // --- NEARBY: 후보 구간과 nextOffset ---

    @Test
    void 첫_배치는_거리순_후보_5곳을_모두_담고_nextOffset은_구간_끝이다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(12));
        stubState(true, true);

        PublicLibraryBatchResponse response = service.nearby(ISBN, LAT, LON, 0);

        assertThat(response.items()).extracting(PublicLibraryBatchItem::libCode)
                .containsExactly("L001", "L002", "L003", "L004", "L005");
        assertThat(response.nextOffset()).isEqualTo(5);
        assertThat(response.stopReason()).isEqualTo(BatchStopReason.NONE);
    }

    @Test
    void 마지막_구간이면_nextOffset은_null이다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(12));
        stubState(true, true);

        PublicLibraryBatchResponse response = service.nearby(ISBN, LAT, LON, 10);

        assertThat(response.items()).hasSize(2);
        assertThat(response.nextOffset()).isNull();
    }

    @Test
    void 후보를_넘는_offset은_빈_결과와_null이다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(3));

        PublicLibraryBatchResponse response = service.nearby(ISBN, LAT, LON, 5);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextOffset()).isNull();
        verifyNoInteractions(snapshotService);
    }

    @Test
    void 미소장도_응답에_남는다_숨김은_화면이_정한다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(1));
        stubState(false, false);

        PublicLibraryBatchItem item = service.nearby(ISBN, LAT, LON, 0).items().get(0);

        assertThat(item.state()).isEqualTo(LibraryState.NOT_HELD);
        assertThat(item.reason()).isNull();
    }

    @Test
    void 거리는_소수_첫째_자리로_반올림하고_기관_정보를_담는다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(List.of(lib(3)));
        stubState(true, false);

        PublicLibraryBatchItem item = service.nearby(ISBN, LAT, LON, 0).items().get(0);

        assertThat(item.state()).isEqualTo(LibraryState.ON_LOAN);
        assertThat(item.distanceKm()).isEqualTo(0.3); // 0.003도 ≈ 0.334km
        assertThat(item.name()).isEqualTo("도서관3");
        assertThat(item.address()).isEqualTo("서울 3");
        assertThat(item.homepage()).isEqualTo("https://lib3.example");
        assertThat(item.latitude()).isEqualTo(LAT + 0.003);
    }

    @Test
    void 범위를_벗어난_좌표는_400() {
        assertThatThrownBy(() -> service.nearby(ISBN, 91.0, LON, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_LOCATION);
        assertThatThrownBy(() -> service.nearby(ISBN, Double.NaN, LON, 0))
                .isInstanceOf(BusinessException.class);
    }

    // --- 기준 기관 모드 ---

    @Test
    void 기준_기관_모드는_그_기관_좌표로_찾고_자기_자신도_후보다() {
        PublicLibrary reference = lib(1);
        when(repository.findByLibCode("L001")).thenReturn(Optional.of(reference));
        when(repository.findWithinRadius(reference.getLat(), reference.getLon(), 10.0)).thenReturn(libs(3));
        stubState(true, true);

        PublicLibraryBatchResponse response = service.nearbyFromLibrary(ISBN, "L001", 0);

        assertThat(response.items().get(0).libCode()).isEqualTo("L001");
        assertThat(response.items().get(0).distanceKm()).isEqualTo(0.0);
    }

    @Test
    void 모르는_기준_기관은_400() {
        when(repository.findByLibCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.nearbyFromLibrary(ISBN, "NOPE", 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNKNOWN_PUBLIC_LIBRARY);
    }

    // --- 실패 상태와 중단 사유 ---

    @Test
    void 조회_실패는_미소장이_아니라_UNKNOWN이고_자동_탐색은_계속할_수_있다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(6));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(1);
            return "L002".equals(code)
                    ? LibraryAvailabilityResult.failed(code)
                    : new LibraryAvailabilityResult(code, true, true, SnapshotSourceStatus.SUCCESS);
        });

        PublicLibraryBatchResponse response = service.nearby(ISBN, LAT, LON, 0);

        PublicLibraryBatchItem failed = response.items().get(1);
        assertThat(failed.state()).isEqualTo(LibraryState.UNKNOWN);
        assertThat(failed.reason()).isEqualTo(LibraryCheckFailure.UPSTREAM_ERROR);
        assertThat(response.stopReason()).isEqualTo(BatchStopReason.NONE);
        assertThat(response.nextOffset()).isEqualTo(5);
    }

    @Test
    void 마감까지_안_끝난_기관은_TIMEOUT이고_배치는_BATCH_DEADLINE으로_멈춘다() throws Exception {
        PublicLibraryBatchService fast = newService(executor, 150L);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(7));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(1);
            if ("L002".equals(code)) {
                release.await(5, TimeUnit.SECONDS);
            }
            return new LibraryAvailabilityResult(code, true, true, SnapshotSourceStatus.SUCCESS);
        });

        try {
            PublicLibraryBatchResponse response = fast.nearby(ISBN, LAT, LON, 0);

            assertThat(response.items().get(0).state()).isEqualTo(LibraryState.AVAILABLE);
            assertThat(response.items().get(1).state()).isEqualTo(LibraryState.UNKNOWN);
            assertThat(response.items().get(1).reason()).isEqualTo(LibraryCheckFailure.TIMEOUT);
            assertThat(response.stopReason()).isEqualTo(BatchStopReason.BATCH_DEADLINE);
            // 멈춰도 진행 위치는 준다 — 사용자가 더보기로 이어 찾을 수 있다
            assertThat(response.nextOffset()).isEqualTo(5);
        } finally {
            release.countDown();
        }
    }

    @Test
    void 풀이_거절한_기관은_POOL_BUSY이고_배치도_POOL_BUSY로_멈춘다() throws Exception {
        // 스레드 1개·큐 없음: 첫 작업이 잡고 있으면 나머지는 거절된다
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(3));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return new LibraryAvailabilityResult(inv.getArgument(1), true, true, SnapshotSourceStatus.SUCCESS);
        });

        try {
            PublicLibraryBatchResponse response = newService(tiny, 150L).nearby(ISBN, LAT, LON, 0);

            assertThat(response.items().get(1).reason()).isEqualTo(LibraryCheckFailure.POOL_BUSY);
            assertThat(response.items().get(2).reason()).isEqualTo(LibraryCheckFailure.POOL_BUSY);
            assertThat(response.stopReason()).isEqualTo(BatchStopReason.POOL_BUSY);
        } finally {
            release.countDown();
            tiny.shutdownNow();
        }
    }

    @Test
    void 조회가_예외를_던지면_UPSTREAM_ERROR이고_탐색은_계속한다() {
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(6));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(1);
            if ("L001".equals(code)) {
                throw new IllegalStateException("스냅샷 읽기 실패");
            }
            return new LibraryAvailabilityResult(code, true, true, SnapshotSourceStatus.SUCCESS);
        });

        PublicLibraryBatchResponse response = service.nearby(ISBN, LAT, LON, 0);

        assertThat(response.items().get(0).state()).isEqualTo(LibraryState.UNKNOWN);
        assertThat(response.items().get(0).reason()).isEqualTo(LibraryCheckFailure.UPSTREAM_ERROR);
        assertThat(response.stopReason()).isEqualTo(BatchStopReason.NONE);
    }

    @Test
    void 마감_뒤_큐에서_아직_시작하지_않은_작업은_정보나루를_부르지_않는다() throws Exception {
        // 스레드 1개 + 큐: 첫 작업이 마감을 넘기면 큐의 나머지 4곳은 시작 전에 취소된다
        ExecutorService single = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(5));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return new LibraryAvailabilityResult(inv.getArgument(1), true, true, SnapshotSourceStatus.SUCCESS);
        });

        try {
            PublicLibraryBatchResponse response = newService(single, 100L).nearby(ISBN, LAT, LON, 0);
            release.countDown();
            single.shutdown();
            assertThat(single.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(calls.get()).isEqualTo(1);
            assertThat(response.items()).allSatisfy(item ->
                    assertThat(item.reason()).isEqualTo(LibraryCheckFailure.TIMEOUT));
            assertThat(response.stopReason()).isEqualTo(BatchStopReason.BATCH_DEADLINE);
        } finally {
            release.countDown();
            single.shutdownNow();
        }
    }

    @Test
    void 남은_시간이_없으면_기다리지_않고_끝나지_않은_기관은_TIMEOUT이다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findWithinRadius(LAT, LON, 10.0)).thenReturn(libs(2));
        when(snapshotService.getAvailability(eq(ISBN), anyString())).thenAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return new LibraryAvailabilityResult(inv.getArgument(1), true, true, SnapshotSourceStatus.SUCCESS);
        });

        try {
            PublicLibraryBatchResponse response = newService(executor, 0L).nearby(ISBN, LAT, LON, 0);

            assertThat(response.items()).extracting(PublicLibraryBatchItem::reason)
                    .containsOnly(LibraryCheckFailure.TIMEOUT);
            assertThat(response.stopReason()).isEqualTo(BatchStopReason.BATCH_DEADLINE);
        } finally {
            release.countDown();
        }
    }

    // --- SAVED ---

    @Test
    void 저장_모드는_요청_순서를_지키고_거리와_nextOffset이_없다() {
        when(repository.findByLibCodeIn(anyCollection())).thenReturn(List.of(lib(1), lib(2), lib(3)));
        stubState(false, false);

        PublicLibraryBatchResponse response = service.saved(ISBN, List.of("L003", "L001", "L003", "L002"));

        assertThat(response.items()).extracting(PublicLibraryBatchItem::libCode)
                .containsExactly("L003", "L001", "L002");
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.distanceKm()).isNull();
            assertThat(item.state()).isEqualTo(LibraryState.NOT_HELD);
        });
        assertThat(response.nextOffset()).isNull();
    }

    @Test
    void 저장_모드는_중복_제거_뒤_5곳을_넘으면_400() {
        assertThatThrownBy(() -> service.saved(ISBN, List.of("A", "B", "C", "D", "E", "F")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PUBLIC_LIBRARY_CODES_LIMIT_EXCEEDED);
        verifyNoInteractions(snapshotService);
    }

    @Test
    void 저장_모드는_중복을_뺀_뒤_5곳이면_조회한다() {
        when(repository.findByLibCodeIn(anyCollection())).thenReturn(libs(5));
        stubState(true, true);

        PublicLibraryBatchResponse response = service.saved(ISBN,
                List.of("L001", "L002", "L003", "L004", "L005", "L001"));

        assertThat(response.items()).hasSize(5);
    }

    @Test
    void 저장_모드에_모르는_코드가_있으면_400() {
        when(repository.findByLibCodeIn(anyCollection())).thenReturn(List.of(lib(1)));

        assertThatThrownBy(() -> service.saved(ISBN, List.of("L001", "NOPE")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNKNOWN_PUBLIC_LIBRARY);
        verifyNoInteractions(snapshotService);
    }
}
