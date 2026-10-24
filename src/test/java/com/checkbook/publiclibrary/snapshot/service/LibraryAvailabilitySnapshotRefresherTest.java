package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryAvailabilitySnapshotRefresherTest {

    @Mock DatanaruClient datanaruClient;
    @Mock LibraryAvailabilitySnapshotPersister persister;

    @InjectMocks
    LibraryAvailabilitySnapshotRefresher refresher;

    @Test
    void refresh_returnsSuccessResult_andCallsPersister() {
        when(datanaruClient.bookExist("9781234567890", "LIB001"))
                .thenReturn(new DatanaruBookExistResult("LIB001", true, true));

        LibraryAvailabilityResult result = refresher.refresh("9781234567890", "LIB001");

        assertThat(result.hasBook()).isTrue();
        assertThat(result.loanAvailable()).isTrue();
        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.SUCCESS);
        verify(persister).upsert("9781234567890", "LIB001", true, true);
    }

    // 만료된 스냅샷을 꺼내지 않는다(D7-0 결정 카드 1) — 스냅샷이 있는 경우는 서킷 통합 시험이 본다
    @Test
    void refreshFallback_returnsFailedResult() {
        LibraryAvailabilityResult result =
                refresher.refreshFallback("9781234567890", "LIB001", new RuntimeException("API 장애"));

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
        assertThat(result.hasBook()).isFalse();
    }
}
