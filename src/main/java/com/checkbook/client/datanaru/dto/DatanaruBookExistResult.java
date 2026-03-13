package com.checkbook.client.datanaru.dto;

public record DatanaruBookExistResult(
        String libCode,
        boolean hasBook,
        boolean loanAvailable
) {
}
