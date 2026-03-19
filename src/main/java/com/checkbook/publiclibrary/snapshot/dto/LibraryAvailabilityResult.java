package com.checkbook.publiclibrary.snapshot.dto;

import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;

public record LibraryAvailabilityResult(
        String libCode,
        boolean hasBook,
        boolean loanAvailable,
        SnapshotSourceStatus sourceStatus
) {
    public static LibraryAvailabilityResult failed(String libCode) {
        return new LibraryAvailabilityResult(libCode, false, false, SnapshotSourceStatus.FAILED);
    }
}
