package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LibraryAvailabilitySnapshotCleanerTest {

    @Mock
    LibraryAvailabilitySnapshotRepository repository;

    @InjectMocks
    LibraryAvailabilitySnapshotCleaner cleaner;

    @Test
    void cleanExpiredSnapshots_deletesRowsOlderThan48Hours() {
        Instant before = Instant.now().minusSeconds(48 * 3600 - 5);

        cleaner.cleanExpiredSnapshots();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByExpiresAtBefore(captor.capture());
        assertThat(captor.getValue()).isBefore(before);
    }
}
