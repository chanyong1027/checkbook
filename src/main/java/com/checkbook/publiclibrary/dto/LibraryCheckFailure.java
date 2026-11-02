package com.checkbook.publiclibrary.dto;

/**
 * UNKNOWN 기관의 확인 실패 이유. 화면은 모르는 값을 "확인 못 함"으로 일반 처리한다(값이 늘어도 깨지지 않게).
 */
public enum LibraryCheckFailure {
    /** 배치 마감까지 끝나지 않음. */
    TIMEOUT,
    /** 정보나루 호출 실패(타임아웃·오류 응답 등). */
    UPSTREAM_ERROR,
    /** 정보나루 서킷이 열려 잠시 부르지 않음(장애 중). */
    CIRCUIT_OPEN,
    /** 오늘 정보나루 호출 예산을 다 써서 부르지 않음. */
    DAILY_LIMIT,
    /** 응답은 왔지만 소장·대출 값이 비었거나 형식이 틀림. 저장하지 않는다. */
    INVALID_RESPONSE,
    /** 공공도서관 풀이 작업을 받지 못함. */
    POOL_BUSY
}
