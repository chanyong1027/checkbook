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

        // 숫자 13자리라도 978/979 접두·체크섬이 맞지 않으면 ISBN이 아니다 (카카오가 주는 서점 내부 코드 등, F1)
        if (digitsOnly.matches("\\d{13}")) {
            return new NormalizedQuery(digitsOnly,
                    isValidIsbn13(digitsOnly) ? QueryType.ISBN : QueryType.NON_STANDARD_ISBN);
        }

        // 변환하면 체크섬이 새로 계산돼 항상 유효해 보이므로 변환 전에 ISBN10 체크섬을 검사한다.
        // 비표준이면 값은 하이픈만 뺀 원래 10자리(끝자리 대문자 X 가능)
        if (digitsOnly.matches("\\d{9}[0-9X]")) {
            return isValidIsbn10(digitsOnly)
                    ? new NormalizedQuery(isbn10ToIsbn13(digitsOnly), QueryType.ISBN)
                    : new NormalizedQuery(digitsOnly, QueryType.NON_STANDARD_ISBN);
        }

        return new NormalizedQuery(normalized, QueryType.KEYWORD);
    }

    public static boolean isValidIsbn13(String value) {
        if (value == null || !value.matches("97[89]\\d{10}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = value.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - (sum % 10)) % 10 == value.charAt(12) - '0';
    }

    static boolean isValidIsbn10(String value) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = value.charAt(i);
            int digit = (c == 'X') ? 10 : c - '0';
            sum += (10 - i) * digit;
        }
        return sum % 11 == 0;
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
        NON_STANDARD_ISBN,
        KEYWORD
    }
}
