package com.checkbook.search.dto;

/**
 * 식별 결과.
 * <ul>
 *   <li>source: 책 정보의 출처 (YES24 찾음 → YES24, 그 밖에 유효한 힌트 → HINT, 아니면 NONE)</li>
 *   <li>yes24: YES24 조회 결과. 비표준 코드라 조회하지 않았으면 SKIPPED</li>
 *   <li>yes24Sent: 실제로 YES24에 요청을 보냈는가. 한도 거절이 우리 쪽(false)인지 YES24(true)인지 구분한다
 *       — 섹션 상태가 SKIPPED/FAILED로 갈린다</li>
 *   <li>price·productUrl: YES24 값만. 힌트로는 채우지 않는다</li>
 * </ul>
 */
public record IdentificationResult(
        Source source,
        Yes24Status yes24,
        boolean yes24Sent,
        IdentifiedBook book,
        Integer price,
        String productUrl
) {

    public enum Source {
        YES24,
        HINT,
        NONE
    }

    public enum Yes24Status {
        FOUND,
        NOT_FOUND,
        TIMEOUT,
        RATE_LIMITED,
        DAILY_LIMIT,
        FAILED,
        SKIPPED
    }
}
