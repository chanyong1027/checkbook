package com.checkbook.common.util;

public final class InputNormalizer {

    private InputNormalizer() {
    }

    public static NormalizedQuery normalize(String rawQuery) {
        if (rawQuery == null) {
            return new NormalizedQuery("", QueryType.KEYWORD);
        }

        String normalized = rawQuery.trim().replaceAll("\\s+", " ");
        String digitsOnly = normalized.replace("-", "").toUpperCase();

        if (digitsOnly.matches("\\d{13}")) {
            return new NormalizedQuery(digitsOnly, QueryType.ISBN);
        }

        if (digitsOnly.matches("\\d{9}[0-9X]")) {
            return new NormalizedQuery(isbn10ToIsbn13(digitsOnly), QueryType.ISBN);
        }

        return new NormalizedQuery(normalized, QueryType.KEYWORD);
    }

    static String isbn10ToIsbn13(String isbn10) {
        String base = "978" + isbn10.substring(0, 9);
        int sum = 0;

        for (int i = 0; i < base.length(); i++) {
            int digit = base.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }

        int checkDigit = (10 - (sum % 10)) % 10;
        return base + checkDigit;
    }

    public record NormalizedQuery(String value, QueryType type) {
    }

    public enum QueryType {
        ISBN,
        KEYWORD
    }
}
