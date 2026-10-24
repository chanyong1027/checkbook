package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalProvider;
import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LibraryAvailabilitySnapshotCircuitBreakerIT {

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
        // 다른 테스트에서 공유되는 서킷브레이커 상태 초기화
        circuitBreakerRegistry.circuitBreaker("datanaru").reset();
        // 만료된 snapshot이 있어도 폴백은 옛 값을 꺼내지 않는다(D7-0 카드 1) — 그걸 확인하려고 넣어 둔다
        // persister.upsert()는 expiresAt을 미래로 설정해 fresh로 인식되므로 repository 직접 사용
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        repository.save(LibraryAvailabilitySnapshot.builder()
                .isbn13("9781234567890")
                .libCode("LIB001")
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
        when(datanaruClient.bookExist("9781234567890", "LIB001"))
                .thenThrow(new RuntimeException("API 장애"));

        // minimum-number-of-calls: 2이므로 2번 호출하면 서킷 OPEN
        snapshotService.getAvailability("9781234567890", "LIB001");
        snapshotService.getAvailability("9781234567890", "LIB001");

        LibraryAvailabilityResult result = snapshotService.getAvailability("9781234567890", "LIB001");

        assertThat(circuitBreakerRegistry.circuitBreaker("datanaru").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
    }

    @Test
    void 예산_소진은_서킷을_열지_않고_실패로_끝난다() {
        when(datanaruClient.bookExist("9781234567890", "LIB001"))
                .thenThrow(new DailyBudgetExceededException(ExternalProvider.DATANARU, Duration.ofHours(1)));

        LibraryAvailabilityResult result = null;
        for (int i = 0; i < 5; i++) {
            result = snapshotService.getAvailability("9781234567890", "LIB001");
        }

        // 호출을 안 한 것이라 정보나루 장애가 아니다 — 실패로 세지 않아 서킷이 닫혀 있어야 한다
        assertThat(circuitBreakerRegistry.circuitBreaker("datanaru").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
    }
}
