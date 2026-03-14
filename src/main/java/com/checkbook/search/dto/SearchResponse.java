package com.checkbook.search.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SearchResponse(
        BookInfo book,
        List<PublicLibraryInfo> publicLibraries,
        UsedBookInfo usedBook,
        List<NewBookInfo> newBooks,
        SearchMetadata metadata
) {

    public record BookInfo(
            String title,
            String author,
            String isbn13,
            String publisher,
            String coverUrl
    ) {
    }

    public record PublicLibraryInfo(
            String libraryName,
            boolean hasBook,
            boolean loanAvailable,
            String address,
            Double latitude,
            Double longitude,
            Double distance,
            String homepage
    ) {
    }

    public record UsedBookInfo(
            Integer userUsedPrice,
            Integer aladinUsedPrice,
            Integer spaceUsedPrice,
            String userUsedUrl,
            String aladinUsedUrl,
            String spaceUsedUrl
    ) {
    }

    public record NewBookInfo(
            String mallName,
            int price,
            String productUrl
    ) {
    }

    public record SearchMetadata(
            LocalDateTime searchedAt,
            List<SectionStatusDetail> sectionStatuses,
            List<FailureDetail> failures
    ) {
    }

    public record SectionStatusDetail(
            SearchSection section,
            SearchSectionStatus status
    ) {
    }

    public record FailureDetail(
            SearchSection section,
            String reason
    ) {
    }
}
