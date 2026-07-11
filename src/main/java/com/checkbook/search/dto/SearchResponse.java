package com.checkbook.search.dto;

import com.checkbook.publiclibrary.dto.PublicLibraryInfo;

import java.time.LocalDateTime;
import java.util.List;

public record SearchResponse(
        BookInfo book,
        List<PublicLibraryInfo> publicLibraries,
        UsedBookInfo usedBook,
        NewBookInfo newBook,
        SubscriptionInfo subscription,
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
            int price,
            String productUrl
    ) {
    }

    public record SubscriptionInfo(
            MillieAvailability millie
            // 추후 RidiAvailability ridi, NaverAvailability naver 추가
    ) {
    }

    public record SearchMetadata(
            LocalDateTime searchedAt,
            List<SectionStatusDetail> sectionStatuses,
            List<FailureDetail> failures,
            Integer publicLibraryTotal,
            boolean publicLibraryHasMore,
            Integer publicLibraryNextOffset
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
