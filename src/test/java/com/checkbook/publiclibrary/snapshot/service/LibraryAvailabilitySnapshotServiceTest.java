package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryAvailabilitySnapshotServiceTest {

    @Mock LibraryAvailabilitySnapshotReader reader;
    @Mock LibraryAvailabilitySnapshotRefresher refresher;

    @InjectMocks
    LibraryAvailabilitySnapshotService service;

    @Test
    void getAvailability_returnsFreshSnapshot_withoutCallingRefresher() {
        LibraryAvailabilityResult fresh = new LibraryAvailabilityResult(
                "LIB001", true, true, SnapshotSourceStatus.SUCCESS
        );
        when(reader.find("9781234567890", "LIB001")).thenReturn(Optional.of(fresh));

        LibraryAvailabilityResult result = service.getAvailability("9781234567890", "LIB001");

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.SUCCESS);
        verify(refresher, never()).refresh("9781234567890", "LIB001");
    }

    @Test
    void getAvailability_callsRefresher_whenNoFreshSnapshotExists() {
        LibraryAvailabilityResult refreshed = new LibraryAvailabilityResult(
                "LIB001", true, false, SnapshotSourceStatus.SUCCESS
        );
        when(reader.find("9781234567890", "LIB001")).thenReturn(Optional.empty());
        when(refresher.refresh("9781234567890", "LIB001")).thenReturn(refreshed);

        LibraryAvailabilityResult result = service.getAvailability("9781234567890", "LIB001");

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.SUCCESS);
        verify(refresher).refresh("9781234567890", "LIB001");
    }

    @Test
    void getAvailability_returnsStaleFallback_whenRefresherReturnsStaleDueToCircuitOpen() {
        LibraryAvailabilityResult stale = new LibraryAvailabilityResult(
                "LIB001", true, false, SnapshotSourceStatus.STALE
        );
        when(reader.find("9781234567890", "LIB001")).thenReturn(Optional.empty());
        when(refresher.refresh("9781234567890", "LIB001")).thenReturn(stale);

        LibraryAvailabilityResult result = service.getAvailability("9781234567890", "LIB001");

        assertThat(result.sourceStatus()).isEqualTo(SnapshotSourceStatus.STALE);
    }
}
