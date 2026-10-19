package com.checkbook.elibrary.client;

import org.jsoup.HttpStatusException;
import org.jsoup.UnsupportedMimeTypeException;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class ELibraryClientException extends RuntimeException {

    /**
     * 실패 종류. 사이트 하나가 죽은 것(접속·HTTP·시간 초과)과 업체가 바꾼 것(구조 변경·파싱 실패·검색 주소 없음)을 나눈다 —
     * 뒤의 셋은 그 업체 사이트가 한꺼번에 깨지고 코드를 고쳐야 한다. 지표 elibrary.scrape의 outcome 태그로 나간다
     */
    public enum Kind {
        /** 결과 목록 DOM도, 빈 결과 문구도 없음 */
        STRUCTURE_CHANGED,
        /** 책 항목은 있는데 하나도 못 읽음 */
        PARSE_FAILED,
        /** 검색 주소가 404 — 업체가 경로를 바꾸면 그 업체 사이트 전부가 이렇게 된다 */
        ENDPOINT_NOT_FOUND,
        /** 404가 아닌 200 외 응답 */
        HTTP_ERROR,
        TIMEOUT,
        /** 연결 거부·DNS·TLS 등 응답을 못 받음 */
        CONNECTION_FAILED
    }

    private final Kind kind;

    public ELibraryClientException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ELibraryClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * Jsoup 입출력 예외를 종류로 나눈다. Jsoup은 200이 아닌 응답(HttpStatusException)과 HTML이 아닌 응답
     * (UnsupportedMimeTypeException)도 IOException으로 던진다 — 뒤의 것은 응답 형식이 바뀐 것이라 구조 변경으로 본다
     */
    public static ELibraryClientException ofIo(String message, IOException cause) {
        Kind kind = cause instanceof HttpStatusException status
                ? (status.getStatusCode() == 404 ? Kind.ENDPOINT_NOT_FOUND : Kind.HTTP_ERROR)
                : cause instanceof UnsupportedMimeTypeException ? Kind.STRUCTURE_CHANGED
                : cause instanceof SocketTimeoutException ? Kind.TIMEOUT
                : Kind.CONNECTION_FAILED;
        return new ELibraryClientException(kind, message, cause);
    }

    public Kind kind() {
        return kind;
    }
}
