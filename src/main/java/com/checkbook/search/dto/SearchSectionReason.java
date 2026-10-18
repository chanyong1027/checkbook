package com.checkbook.search.dto;

/**
 * 섹션 상태의 사유 코드 (T1 계약 2절). 프런트는 이 코드로 안내 문구를 고르고,
 * 모르는 코드는 status로 처리한다. 사람이 읽는 원인은 failures[].reason.
 */
public enum SearchSectionReason {
    /** 978/979 접두·체크섬이 맞지 않는 코드라 ISBN 기반 조회를 생략 (SKIPPED) */
    NON_STANDARD_ISBN,
    /** 위치가 없어 도서관 조회를 생략 (SKIPPED) */
    NO_LOCATION,
    /** 책 정보(제목)가 없어 밀리 조회를 생략 (SKIPPED) */
    NO_METADATA,
    /** YES24에 상품이 없음 — 장애가 아닌 정상 결과 (SUCCESS) */
    NOT_FOUND,
    /** YES24가 책은 찾았지만 가격·구매 링크가 없음 (SUCCESS) */
    PRICE_UNAVAILABLE,
    /** 시간 초과 (FAILED) */
    TIMEOUT,
    /** YES24 초당 한도 — 우리 쪽 선제한이면 SKIPPED, YES24 429면 FAILED */
    RATE_LIMITED,
    /**
     * 하루 한도. YES24: 중단 중이거나 우리 예산 차단선이면 SKIPPED, YES24 429면 FAILED.
     * 정보나루(도서관)·밀리(구독): 우리 하루 예산 차단선이라 호출하지 않음(SKIPPED, 요청 제한 결정 2026-10-18)
     */
    DAILY_LIMIT,
    /** 외부 호출 오류·응답 검증 실패 또는 executor 거절(풀 포화) (FAILED). 원인은 failures[].reason */
    UNAVAILABLE
}
