package com.checkbook.publiclibrary.dto;

/**
 * 배치 응답의 기관별 소장 상태(D7-1). 조회 실패를 미소장(false)으로 뭉개지 않으려고 4상태로 나눈다.
 * 정보나루 대출 자료는 전날 기준이다 — AVAILABLE을 실시간 대출 가능으로 표현하지 않는다.
 */
public enum LibraryState {
    /** 소장 + 대출 가능. */
    AVAILABLE,
    /** 소장 + 대출중. */
    ON_LOAN,
    /** 미소장. 소장 N이면 대출 가능 값과 무관하게 여기다. */
    NOT_HELD,
    /** 확인 못 함. 이유는 {@link LibraryCheckFailure}. */
    UNKNOWN;

    public static LibraryState of(boolean hasBook, boolean loanAvailable) {
        if (!hasBook) {
            return NOT_HELD;
        }
        return loanAvailable ? AVAILABLE : ON_LOAN;
    }
}
