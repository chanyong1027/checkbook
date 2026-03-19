package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotPersister {

    static final long TTL_HOURS = 24;

    private final LibraryAvailabilitySnapshotRepository repository;

    @Transactional
    public void upsert(String isbn13, String libCode, boolean hasBook, boolean loanAvailable) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TTL_HOURS, ChronoUnit.HOURS);

        repository.findByIsbn13AndLibCode(isbn13, libCode)
                .ifPresentOrElse(
                        snapshot -> snapshot.update(hasBook, loanAvailable, now, expiresAt),
                        () -> repository.save(LibraryAvailabilitySnapshot.builder()
                                .isbn13(isbn13).libCode(libCode)
                                .hasBook(hasBook).loanAvailable(loanAvailable)
                                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                                .lastFetchedAt(now).expiresAt(expiresAt)
                                .build())
                );
    }
}
