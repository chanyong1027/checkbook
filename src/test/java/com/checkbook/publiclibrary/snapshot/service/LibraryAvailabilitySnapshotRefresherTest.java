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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryAvailabilitySnapshotRefresherTest {

    @Mock DatanaruClient datanaruClient;
    @Mock LibraryAvailabilitySnapshotPersister persister;
    @Mock LibraryAvailabilitySnapshotReader reader;

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

    @Test
    void refreshFallback_returnsStaleSnapshot_whenExists() {
        LibraryAvailabilityResult stale = new LibraryAvailabilityResult(
                "LIB001", true, false, SnapshotSourceStatus.STALE
        );
        when(reader.findStale("9781234567890", "LIB001")).thenReturn(Optional.of(stale));

        LibraryAvailabilityResult result =
                refresher.refreshFallback("9781234567890", "LIB001", new RuntimeException("API 장애"));

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.STALE);
        assertThat(result.hasBook()).isTrue();
    }

    @Test
    void refreshFallback_returnsFailedResult_whenNoSnapshotExists() {
        when(reader.findStale("9781234567890", "LIB001")).thenReturn(Optional.empty());

        LibraryAvailabilityResult result =
                refresher.refreshFallback("9781234567890", "LIB001", new RuntimeException("API 장애"));

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.FAILED);
        assertThat(result.hasBook()).isFalse();
    }
}
