package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LibraryAvailabilitySnapshotRefresherTest {

    private static final String ISBN = "9781234567890";
    private static final String LIB_CODE = "LIB001";

    private final DatanaruClient datanaruClient = mock(DatanaruClient.class);
    private final LibraryAvailabilitySnapshotPersister persister = mock(LibraryAvailabilitySnapshotPersister.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LibraryAvailabilitySnapshotRefresher refresher =
            new LibraryAvailabilitySnapshotRefresher(datanaruClient, persister, circuitBreakerRegistry, meterRegistry);

    @Test
    void 정보나루가_답하면_저장하고_SUCCESS를_돌려준다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE)).thenReturn(new DatanaruBookExistResult(LIB_CODE, true, false));

        LibraryAvailabilityResult result = refresher.refresh(ISBN, LIB_CODE);

        assertThat(result).isEqualTo(new LibraryAvailabilityResult(LIB_CODE, true, false, SnapshotSourceStatus.SUCCESS));
        verify(persister).upsert(ISBN, LIB_CODE, true, false);
        assertThat(persistCount("success")).isEqualTo(1);
    }

    // 전에는 저장까지 서킷 안이라 저장 예외가 폴백으로 가서, 정보나루에서 받은 결과를 버리고 FAILED였다(A)
    @Test
    void 저장이_실패해도_받은_결과를_돌려주고_저장_실패로_센다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE)).thenReturn(new DatanaruBookExistResult(LIB_CODE, true, true));
        doThrow(new DataIntegrityViolationException("23505")).when(persister).upsert(ISBN, LIB_CODE, true, true);

        LibraryAvailabilityResult result = refresher.refresh(ISBN, LIB_CODE);

        assertThat(result).isEqualTo(new LibraryAvailabilityResult(LIB_CODE, true, true, SnapshotSourceStatus.SUCCESS));
        assertThat(persistCount("failure")).isEqualTo(1);
        assertThat(persistCount("success")).isZero();
    }

    // 서킷은 정보나루의 건강만 잰다 — 우리 DB 문제로 정보나루 호출을 막으면 안 된다
    @Test
    void 저장_실패는_서킷이_정보나루_실패로_세지_않는다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE)).thenReturn(new DatanaruBookExistResult(LIB_CODE, true, true));
        doThrow(new DataIntegrityViolationException("23505")).when(persister).upsert(anyString(), anyString(), anyBoolean(), anyBoolean());

        for (int i = 0; i < 20; i++) {
            refresher.refresh(ISBN, LIB_CODE);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(LibraryAvailabilitySnapshotRefresher.CIRCUIT_BREAKER_NAME);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void 정보나루가_실패하면_저장하지_않고_FAILED를_돌려준다() {
        when(datanaruClient.bookExist(ISBN, LIB_CODE)).thenThrow(new RuntimeException("API 장애"));

        LibraryAvailabilityResult result = refresher.refresh(ISBN, LIB_CODE);

        assertThat(result).isEqualTo(LibraryAvailabilityResult.failed(LIB_CODE));
        verifyNoInteractions(persister);
        assertThat(circuitBreakerRegistry.circuitBreaker(LibraryAvailabilitySnapshotRefresher.CIRCUIT_BREAKER_NAME)
                .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 서킷이_열려_있으면_정보나루를_부르지_않고_FAILED를_돌려준다() {
        circuitBreakerRegistry.circuitBreaker(LibraryAvailabilitySnapshotRefresher.CIRCUIT_BREAKER_NAME).transitionToOpenState();

        LibraryAvailabilityResult result = refresher.refresh(ISBN, LIB_CODE);

        assertThat(result).isEqualTo(LibraryAvailabilityResult.failed(LIB_CODE));
        verify(datanaruClient, never()).bookExist(anyString(), anyString());
        verifyNoInteractions(persister);
    }

    @Test
    void 저장_결과_지표는_기동_때_0으로_있다() {
        assertThat(persistCount("success")).isZero();
        assertThat(persistCount("failure")).isZero();
    }

    private double persistCount(String result) {
        return meterRegistry.get("library.snapshot.persist").tag("result", result).counter().count();
    }
}
