package com.checkbook.elibrary.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ELibrarySearchResponse(
        List<ELibraryResult> results,
        ELibrarySearchMetadata metadata
) {

    public record ELibraryResult(
            Long libraryId,
            String libraryName,
            String vendorType,
            List<ELibraryBook> books,
            ELibrarySearchStatus status,
            long elapsedMs
    ) {
    }

    public record ELibraryBook(
            String title,
            String author,
            String publisher,
            String coverUrl,
            boolean available,
            String detailUrl
    ) {
    }

    public record ELibrarySearchMetadata(
            long totalElapsedMs,
            LocalDateTime searchedAt,
            List<ELibraryFailureDetail> failures
    ) {
    }

    public record ELibraryFailureDetail(
            Long libraryId,
            String libraryName,
            String reason
    ) {
    }
}
