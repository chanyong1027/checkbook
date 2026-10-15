package com.checkbook.client.millie;

/**
 * 밀리 검색 실패(호출 오류, 타임아웃, 오류 응답 코드, 응답 구조 누락).
 * 정상 0건(RESP_CD 200 + content.list [])은 예외가 아니라 빈 목록이다.
 * 메시지는 응답의 failures[].reason으로 노출되므로 응답 원문을 넣지 않는다.
 */
public class MillieClientException extends RuntimeException {

    public MillieClientException(String message) {
        super(message);
    }

    public MillieClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
