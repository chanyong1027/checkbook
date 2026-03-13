package com.checkbook.client.aladin.dto;

public record AladinSearchResult(
        String isbn13,
        String title,
        String author,
        String publisher,
        String coverUrl
) {
}
