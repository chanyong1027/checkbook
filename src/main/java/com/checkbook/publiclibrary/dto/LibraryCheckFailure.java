package com.checkbook.publiclibrary.dto;

/**
 * UNKNOWN 기관의 확인 실패 이유. 화면은 모르는 값을 "확인 못 함"으로 일반 처리한다(값이 늘어도 깨지지 않게).
 * 서킷 열림·하루 예산 소진은 지금 UPSTREAM_ERROR로 합쳐지고, 다음 PR에서 따로 나눈다.
 */
public enum LibraryCheckFailure {
    /** 배치 마감까지 끝나지 않음. */
    TIMEOUT,
    /** 정보나루 호출 실패(서킷·예산 포함). */
    UPSTREAM_ERROR,
    /** 공공도서관 풀이 작업을 받지 못함. */
    POOL_BUSY
}
