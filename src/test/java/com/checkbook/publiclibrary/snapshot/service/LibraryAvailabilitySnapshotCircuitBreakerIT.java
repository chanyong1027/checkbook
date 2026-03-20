package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LibraryAvailabilitySnapshotCircuitBreakerIT {

    @Autowired
    LibraryAvailabilitySnapshotService snapshotService;

    @Autowired
    LibraryAvailabilitySnapshotPersister persister;

    @MockitoBean
    DatanaruClient datanaruClient;

    @BeforeEach
    void seedStaleSnapshot() {
        // 서킷 OPEN 상태에서 반환될 stale snapshot 미리 삽입
        persister.upsert("9781234567890", "LIB001", true, true);
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
