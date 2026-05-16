package com.checkbook.featured.snapshot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "featured_section_snapshot")
public class FeaturedSectionSnapshot {

    private static final int MAX_FAILURE_REASON_LEN = 500;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "section_type", length = 20)
    private FeaturedSectionType sectionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private FeaturedSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SnapshotStatus status;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Builder
    public FeaturedSectionSnapshot(
            FeaturedSectionType sectionType,
            FeaturedSource source,
            SnapshotStatus status,
            Instant lastFetchedAt,
            Instant expiresAt,
            String failureReason
    ) {
        this.sectionType = sectionType;
        this.source = source;
        this.status = status;
        this.lastFetchedAt = lastFetchedAt;
        this.expiresAt = expiresAt;
        this.failureReason = failureReason;
    }

    public void markSuccess(Instant fetchedAt, Instant expiresAt) {
        this.status = SnapshotStatus.SUCCESS;
        this.lastFetchedAt = fetchedAt;
        this.expiresAt = expiresAt;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = SnapshotStatus.FAILED;
        this.failureReason = truncate(reason);
        // lastFetchedAt, expiresAt 보존
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_FAILURE_REASON_LEN) {
            return value;
        }
        return value.substring(0, MAX_FAILURE_REASON_LEN);
    }
}
