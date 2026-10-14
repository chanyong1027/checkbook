package com.checkbook.client.yes24;

/**
 * YES24 조회 실패. '책 없음'(GOODS_002)은 실패가 아니라 빈 결과로 돌려준다.
 * sent=false는 우리 쪽에서 호출을 생략했다는 뜻이다(선제한 거절, 일일 중단) — 섹션 상태는 SKIPPED가 된다.
 */
public class Yes24Exception extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        RATE_LIMITED,
        DAILY_LIMIT,
        FAILED
    }

    private final Kind kind;
    private final boolean sent;

    public Yes24Exception(Kind kind, boolean sent, String message) {
        this(kind, sent, message, null);
    }

    public Yes24Exception(Kind kind, boolean sent, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.sent = sent;
    }

    public Kind kind() {
        return kind;
    }

    public boolean sent() {
        return sent;
    }
}
