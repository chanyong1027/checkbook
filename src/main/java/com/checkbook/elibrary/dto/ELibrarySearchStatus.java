package com.checkbook.elibrary.dto;

public enum ELibrarySearchStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    /** 이 도서관 사이트의 하루 수집 상한에 닿아 수집하지 않음(요청 제한 결정 카드 1) */
    DAILY_LIMIT
}
