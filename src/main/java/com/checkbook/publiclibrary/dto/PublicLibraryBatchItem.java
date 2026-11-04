package com.checkbook.publiclibrary.dto;

import com.checkbook.publiclibrary.link.PublicLibrarySearchLink;

/**
 * 배치에서 선정한 기관 하나. 실패해도 빠지지 않고 UNKNOWN으로 남는다(지도에 "확인 못 함"으로 표시하려고).
 *
 * @param distanceKm 기준점에서 직선거리(소수 첫째 자리). 저장 모드(SAVED)는 기준점이 없어 null
 * @param reason     state가 UNKNOWN일 때만 값이 있다
 * @param searchLink 그 도서관 사이트에서 이 책을 검색하는 링크. 형식이 없거나 제목을 받지 못하면 null(화면은 홈페이지 링크)
 */
public record PublicLibraryBatchItem(
        String libCode,
        String name,
        String address,
        double latitude,
        double longitude,
        Double distanceKm,
        String homepage,
        LibraryState state,
        LibraryCheckFailure reason,
        PublicLibrarySearchLink searchLink
) {
}
