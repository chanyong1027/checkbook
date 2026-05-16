package com.checkbook.client.datanaru.dto;

public record DatanaruLoanBookResult(
        int ranking,
        String isbn13,
        String title,
        String author,
        String publisher,
        String coverUrl,
        String publishedAt   // 'YYYY' 또는 'YYYY-MM-DD'
) {
}
