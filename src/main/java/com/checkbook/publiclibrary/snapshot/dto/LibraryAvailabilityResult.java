package com.checkbook.publiclibrary.snapshot.dto;

import com.checkbook.publiclibrary.snapshot.domain.SnapshotFailureCause;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;

/**
 * @param failureCause sourceStatus가 FAILED일 때만 값이 있다(성공이면 null)
 */
public record LibraryAvailabilityResult(
        String libCode,
        boolean hasBook,
        boolean loanAvailable,
        SnapshotSourceStatus sourceStatus,
        SnapshotFailureCause failureCause
) {
    public LibraryAvailabilityResult(String libCode, boolean hasBook, boolean loanAvailable,
                                     SnapshotSourceStatus sourceStatus) {
        this(libCode, hasBook, loanAvailable, sourceStatus, null);
    }

    public static LibraryAvailabilityResult failed(String libCode) {
        return failed(libCode, SnapshotFailureCause.UPSTREAM_ERROR);
    }

    public static LibraryAvailabilityResult failed(String libCode, SnapshotFailureCause cause) {
        return new LibraryAvailabilityResult(libCode, false, false, SnapshotSourceStatus.FAILED, cause);
    }
}
