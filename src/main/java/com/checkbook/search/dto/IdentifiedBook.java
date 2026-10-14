package com.checkbook.search.dto;

/**
 * 공급자 중립 도서 정보. 출처(YES24·힌트)는 {@link IdentificationResult#source()}가 말한다.
 * 출처가 NONE이면 isbn13만 있고 나머지는 null이다.
 */
public record IdentifiedBook(
        String title,
        String author,
        String isbn13,
        String publisher,
        String coverUrl
) {

    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }
}
