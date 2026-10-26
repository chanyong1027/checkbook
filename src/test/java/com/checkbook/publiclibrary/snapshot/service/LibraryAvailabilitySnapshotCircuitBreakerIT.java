package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalProvider;
import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import com.checkbook.support.PostgresIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정보나루 서킷과 스냅샷 저장을 실제 배선으로 본다. 저장이 PostgreSQL 전용 문장(ON CONFLICT)이라 PostgreSQL 묶음에서 돈다
 * (DatanaruClient만 가짜, 저장은 진짜)
 */
class LibraryAvailabilitySnapshotCircuitBreakerIT extends PostgresIntegrationTest {

    private static final String ISBN = "9781234567890";
    private static final String LIB_CODE = "LIB001";

    @Autowired
    LibraryAvailabilitySnapshotService snapshotService;

    @Autowired
    LibraryAvailabilitySnapshotRepository repository;

    @MockitoBean
    DatanaruClient datanaruClient;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        truncate("library_availability_snapshot");
        // 다른 테스트에서 공유되는 서킷브레이커 상태 초기화
        circuitBreakerRegistry.circuitBreaker("datanaru").reset();
        // 만료된 snapshot이 있어도 폴백은 옛 값을 꺼내지 않는다(D7-0 카드 1) — 그걸 확인하려고 넣어 둔다
        // persister.upsert()는 expiresAt을 미래로 설정해 fresh로 인식되므로 repository 직접 사용
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        repository.save(LibraryAvailabilitySnapshot.builder()
                .isbn13(ISBN)
                .libCode(LIB_CODE)
                .hasBook(true)
                .loanAvailable(true)
                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                .lastFetchedAt(past)
                .expiresAt(past)
                .build());
    }

    @Test
    void 연속_실패로_서킷이_열리면_만료된_스냅샷이_있어도_실패를_돌려준다() {
        // DatanaruClient가 계속 실패하도록 설정
        when(datanaruClient.bookExist(ISBN, LIB_CODE))
                .thenThrow(new RuntimeException("API 장애"));

        // minimum-number-of-calls: 2이므로 2번 호출하면 서킷 OPEN
        snapshotService.getAvailability(ISBN, LIB_CODE);
        snapshotService.getAvailability(ISBN, LIB_CODE);

        LibraryAvailabilityResult result = snapshotService.getAvailability(ISBN, LIB_CODE);

        assertThat(circuitBreakerRegistry.circuitBreaker("datanaru").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
    }

    @Test
    void 예산_소진은_서킷을_열지_않고_실패로_끝난다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE))
                .thenThrow(new DailyBudgetExceededException(ExternalProvider.DATANARU, Duration.ofHours(1)));

        LibraryAvailabilityResult result = null;
        for (int i = 0; i < 5; i++) {
            result = snapshotService.getAvailability(ISBN, LIB_CODE);
        }

        // 호출을 안 한 것이라 정보나루 장애가 아니다 — 실패로 세지 않아 서킷이 닫혀 있어야 한다
        assertThat(circuitBreakerRegistry.circuitBreaker("datanaru").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
    }

    // 만료된 행이 있는 키를 다시 받으면 같은 문장(ON CONFLICT)이 갱신으로 간다 — 행은 하나, 값과 만료 시각만 바뀐다
    @Test
    void 정보나루가_답하면_만료된_스냅샷을_새_값으로_갱신하고_다음_조회는_스냅샷에서_읽는다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE)).thenReturn(new DatanaruBookExistResult(LIB_CODE, true, false));

        Instant before = Instant.now();
        LibraryAvailabilityResult first = snapshotService.getAvailability(ISBN, LIB_CODE);
        LibraryAvailabilityResult second = snapshotService.getAvailability(ISBN, LIB_CODE);

        assertThat(first).isEqualTo(new LibraryAvailabilityResult(LIB_CODE, true, false, SnapshotSourceStatus.SUCCESS));
        assertThat(second).isEqualTo(first);
        LibraryAvailabilitySnapshot saved = repository.findByIsbn13AndLibCode(ISBN, LIB_CODE).orElseThrow();
        assertThat(saved.isLoanAvailable()).isFalse();
        // 시간대가 밀려 저장되면(±몇 시간) 잡히게 좁게 본다 — 받은 시각은 지금, 만료는 정확히 24시간 뒤
        assertThat(saved.getLastFetchedAt()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(Duration.between(saved.getLastFetchedAt(), saved.getExpiresAt()))
                .isEqualTo(Duration.ofHours(LibraryAvailabilitySnapshotPersister.TTL_HOURS));
        assertThat(repository.count()).isEqualTo(1);
        verify(datanaruClient, times(1)).bookExist(ISBN, LIB_CODE);
    }
}
