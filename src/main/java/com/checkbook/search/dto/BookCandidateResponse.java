package com.checkbook.search.dto;

import java.util.List;

public record BookCandidateResponse(
        List<BookCandidate> items,
        Pagination pagination
) {
    public record BookCandidate(
            String title,
            String author,
            String publisher,
            String isbn13,
            String coverUrl,
            String publishedAt
    ) {
    }

    public record Pagination(
            int page,
            int size,
            int totalCount,
            boolean isEnd
    ) {
    }
}
