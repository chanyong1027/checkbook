package com.checkbook.client.kakao;

/**
 * 카카오 책 검색을 부르지 못했거나 응답을 읽을 수 없다(HTTP 오류·시간 초과·연결 실패·본문 없음·meta나 documents 누락).
 * 실제 0건(200 + documents [])과 구분한다 — 예전에는 빈 목록으로 삼켜 화면이 "검색 결과가 없어요"였다.
 * GlobalExceptionHandler가 503 + BOOK_SEARCH_UNAVAILABLE로 바꾼다.
 */
public class KakaoSearchUnavailableException extends RuntimeException {

    public KakaoSearchUnavailableException(Throwable cause) {
        super("카카오 도서 검색 실패", cause);
    }

    public KakaoSearchUnavailableException(String message) {
        super(message);
    }
}
