package com.checkbook.search.dto;

import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;

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
            Integer publicLibraryNextOffset,
            IdentificationInfo identification
    ) {
    }

    /**
     * 책 정보의 출처와 YES24 조회 결과. '힌트로 표시됨'의 원인을 알 수 있게 한다.
     */
    public record IdentificationInfo(
            Source source,
            Yes24Status yes24
    ) {
    }

    /**
     * reason은 기계용 코드(nullable). 프런트 안내 문구는 이 코드로 고른다.
     * 사람이 읽는 실패 원인은 failures[].reason에 따로 있다.
     */
    public record SectionStatusDetail(
            SearchSection section,
            SearchSectionStatus status,
            SearchSectionReason reason
    ) {
    }

    public record FailureDetail(
            SearchSection section,
            String reason
    ) {
    }
}
