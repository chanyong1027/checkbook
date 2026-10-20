package com.checkbook.client.yes24;

/**
 * 다시 보내면 나을 수 있는 YES24 실패: 타임아웃, 연결 실패, 5xx. 늘 보낸 요청이다(sent=true).
 * 식별 경로의 재시도와 서킷은 이 타입만 센다 — 429·4xx·응답 검증 실패는 다시 보내도 같은 답이라 넣지 않는다
 * (T4 결정 카드 T3, 규칙 2). 상위 타입이 그대로라 {@code catch (Yes24Exception)}와 kind 매핑은 바뀌지 않는다.
 */
public class Yes24TransientException extends Yes24Exception {

    public Yes24TransientException(Kind kind, String message) {
        super(kind, true, message);
    }

    public Yes24TransientException(Kind kind, String message, Throwable cause) {
        super(kind, true, message, cause);
    }
}
