package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

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
        // 서킷 OPEN 시 폴백에서 반환될 만료된(stale) snapshot 삽입
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
    void circuitBreakerOpens_andReturnsStaleFallback_afterConsecutiveFailures() {
        // DatanaruClient가 계속 실패하도록 설정
        when(datanaruClient.bookExist("9781234567890", "LIB001"))
                .thenThrow(new RuntimeException("API 장애"));

        // minimum-number-of-calls: 2이므로 2번 호출하면 서킷 OPEN
        snapshotService.getAvailability("9781234567890", "LIB001");
        snapshotService.getAvailability("9781234567890", "LIB001");

        // 서킷 OPEN 상태에서 stale fallback 반환 확인
        LibraryAvailabilityResult result = snapshotService.getAvailability("9781234567890", "LIB001");

        assertThat(result.sourceStatus())
                .isIn(SnapshotSourceStatus.STALE, SnapshotSourceStatus.FAILED);
    }
}
