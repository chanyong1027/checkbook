package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotReader {

    private final LibraryAvailabilitySnapshotRepository repository;

    /** fresh snapshot이면 반환, 없거나 만료됐으면 empty */
    @Transactional(readOnly = true)
    public Optional<LibraryAvailabilityResult> find(String isbn13, String libCode) {
        return repository.findByIsbn13AndLibCode(isbn13, libCode)
                .filter(snapshot -> snapshot.getExpiresAt().isAfter(Instant.now()))
                .map(this::toResult);
    }

    /** 만료 여부 무관하게 있으면 반환 (폴백용), sourceStatus는 STALE로 강제 */
    @Transactional(readOnly = true)
    public Optional<LibraryAvailabilityResult> findStale(String isbn13, String libCode) {
        return repository.findByIsbn13AndLibCode(isbn13, libCode)
                .map(snapshot -> new LibraryAvailabilityResult(
                        snapshot.getLibCode(),
                        snapshot.isHasBook(),
                        snapshot.isLoanAvailable(),
                        SnapshotSourceStatus.STALE
                ));
    }

    private LibraryAvailabilityResult toResult(LibraryAvailabilitySnapshot snapshot) {
        return new LibraryAvailabilityResult(
                snapshot.getLibCode(),
                snapshot.isHasBook(),
                snapshot.isLoanAvailable(),
                snapshot.getSourceStatus()
        );
    }
}
