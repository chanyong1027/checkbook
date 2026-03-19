package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryAvailabilitySnapshotReaderTest {

    @Mock
    LibraryAvailabilitySnapshotRepository repository;

    @InjectMocks
    LibraryAvailabilitySnapshotReader reader;

    @Test
    void find_returnsEmpty_whenNoSnapshotExists() {
        when(repository.findByIsbn13AndLibCode("9781234567890", "LIB001"))
                .thenReturn(Optional.empty());

        assertThat(reader.find("9781234567890", "LIB001")).isEmpty();
    }

    @Test
    void find_returnsFreshResult_whenExpiresAtIsInFuture() {
        LibraryAvailabilitySnapshot snapshot = LibraryAvailabilitySnapshot.builder()
                .isbn13("9781234567890").libCode("LIB001")
                .hasBook(true).loanAvailable(true)
                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                .lastFetchedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(repository.findByIsbn13AndLibCode("9781234567890", "LIB001"))
                .thenReturn(Optional.of(snapshot));

        Optional<LibraryAvailabilityResult> result = reader.find("9781234567890", "LIB001");

        assertThat(result).isPresent();
        assertThat(result.get().sourceStatus()).isEqualTo(SnapshotSourceStatus.SUCCESS);
        assertThat(result.get().hasBook()).isTrue();
    }

    @Test
    void find_returnsEmpty_whenSnapshotIsExpired() {
        LibraryAvailabilitySnapshot snapshot = LibraryAvailabilitySnapshot.builder()
                .isbn13("9781234567890").libCode("LIB001")
                .hasBook(true).loanAvailable(false)
                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                .lastFetchedAt(Instant.now().minusSeconds(90000))
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();
        when(repository.findByIsbn13AndLibCode("9781234567890", "LIB001"))
                .thenReturn(Optional.of(snapshot));

        assertThat(reader.find("9781234567890", "LIB001")).isEmpty();
    }

    @Test
    void findStale_returnsResult_withStaleStatus_evenWhenExpired() {
        LibraryAvailabilitySnapshot snapshot = LibraryAvailabilitySnapshot.builder()
                .isbn13("9781234567890").libCode("LIB001")
                .hasBook(true).loanAvailable(false)
                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                .lastFetchedAt(Instant.now().minusSeconds(90000))
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();
        when(repository.findByIsbn13AndLibCode("9781234567890", "LIB001"))
                .thenReturn(Optional.of(snapshot));

        Optional<LibraryAvailabilityResult> result = reader.findStale("9781234567890", "LIB001");

        assertThat(result).isPresent();
        assertThat(result.get().sourceStatus()).isEqualTo(SnapshotSourceStatus.STALE);
        assertThat(result.get().hasBook()).isTrue();
    }
}
