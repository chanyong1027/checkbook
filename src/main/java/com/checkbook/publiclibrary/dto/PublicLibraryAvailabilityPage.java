package com.checkbook.publiclibrary.dto;

import java.util.List;

/**
 * 서비스 반환 도메인 페이지.
 * - libraries: availability 조회에 성공한 항목만(거리순).
 * - total: max-count(20)로 캡된 근처 후보 수(전체 후보 수 아님).
 * - nextOffset: 다음 더보기 요청에 그대로 쓸 offset. 마지막 페이지면 null.
 * - hasMoreLibraries: 더 조회할 근처 도서관 후보가 있음(책 소장 여부 아님).
 * - failedCount: 이번 페이지에서 시도했으나 availability 실패로 빠진 항목 수.
 */
public record PublicLibraryAvailabilityPage(
        List<PublicLibraryInfo> libraries,
        int offset,
        int total,
        Integer nextOffset,
        boolean hasMoreLibraries,
        int failedCount
) {
}
