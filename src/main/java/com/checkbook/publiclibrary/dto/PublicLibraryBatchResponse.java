package com.checkbook.publiclibrary.dto;

import java.util.List;

/**
 * @param items      선정 순서(주변은 거리순, 저장 모드는 요청 순서)
 * @param nextOffset 다음 구간의 시작 위치. 표시 개수나 소장 수가 아니라 이번에 선정한 후보 구간의 끝이다.
 *                   null은 후보 소진만 뜻한다(시간·풀 때문에 멈춘 것은 stopReason이 알린다)
 */
public record PublicLibraryBatchResponse(
        List<PublicLibraryBatchItem> items,
        Integer nextOffset,
        BatchStopReason stopReason
) {
}
