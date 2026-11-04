package com.checkbook.publiclibrary.link;

import java.util.Map;

/**
 * 도서관 사이트에서 이 책을 검색하는 링크 하나. 사용자 브라우저가 연다(서버는 도서관 사이트를 부르지 않는다).
 *
 * @param method GET이면 {@code url}을 그대로 연다. POST면 {@code url}로 {@code fields}를 새 탭에서 제출한다
 *               (주소에 검색어가 남지 않는 사이트 — 결정 2026-11-04 POST 자동 제출 카드 A안)
 * @param fields POST일 때만 값이 있다. 검색어는 원문이고 인코딩은 브라우저가 한다
 */
public record PublicLibrarySearchLink(Kind kind, String method, String url, Map<String, String> fields) {

    public enum Kind {
        /** 그 사이트의 제목 검색 결과 화면으로 바로 간다. */
        RESULT,
        /** 검색 화면까지만 연다(검색어를 넣을 수 없는 사이트). */
        SEARCH_PAGE
    }
}
