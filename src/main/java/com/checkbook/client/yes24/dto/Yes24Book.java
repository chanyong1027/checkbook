package com.checkbook.client.yes24.dto;

/**
 * 검증을 통과한 YES24 도서 정보. salePrice는 가격이 없거나(null) 0 이하이면 null.
 */
public record Yes24Book(
        String title,
        String author,
        String publisher,
        String isbn13,
        Integer salePrice,
        String link,
        String coverUrl
) {
}
