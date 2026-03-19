package com.checkbook.publiclibrary.snapshot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "library_availability_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_las_isbn13_lib_code",
                columnNames = {"isbn13", "lib_code"}
        ),
        indexes = @Index(name = "idx_las_expires_at", columnList = "expires_at")
)
public class LibraryAvailabilitySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "isbn13", nullable = false, length = 13)
    private String isbn13;

    @Column(name = "lib_code", nullable = false, length = 20)
    private String libCode;

    @Column(name = "has_book", nullable = false)
    private boolean hasBook;

    @Column(name = "loan_available", nullable = false)
    private boolean loanAvailable;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_status", nullable = false, length = 20)
    private SnapshotSourceStatus sourceStatus;

    @Column(name = "last_fetched_at", nullable = false)
    private Instant lastFetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder
    public LibraryAvailabilitySnapshot(
            String isbn13,
            String libCode,
            boolean hasBook,
            boolean loanAvailable,
            SnapshotSourceStatus sourceStatus,
            Instant lastFetchedAt,
            Instant expiresAt
    ) {
        this.isbn13 = isbn13;
        this.libCode = libCode;
        this.hasBook = hasBook;
        this.loanAvailable = loanAvailable;
        this.sourceStatus = sourceStatus;
        this.lastFetchedAt = lastFetchedAt;
        this.expiresAt = expiresAt;
    }

    public void update(boolean hasBook, boolean loanAvailable, Instant lastFetchedAt, Instant expiresAt) {
        this.hasBook = hasBook;
        this.loanAvailable = loanAvailable;
        this.sourceStatus = SnapshotSourceStatus.SUCCESS;
        this.lastFetchedAt = lastFetchedAt;
        this.expiresAt = expiresAt;
    }
}
