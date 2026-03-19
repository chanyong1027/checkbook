package com.checkbook.publiclibrary.snapshot.repository;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface LibraryAvailabilitySnapshotRepository extends JpaRepository<LibraryAvailabilitySnapshot, Long> {

    Optional<LibraryAvailabilitySnapshot> findByIsbn13AndLibCode(String isbn13, String libCode);

    @Transactional
    void deleteByExpiresAtBefore(Instant cutoff);
}
