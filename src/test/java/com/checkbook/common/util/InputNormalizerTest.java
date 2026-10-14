package com.checkbook.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputNormalizerTest {

    @Test
    void normalizeKeywordTrimmed() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("  자바 프로그래밍  ");

        assertThat(result.value()).isEqualTo("자바 프로그래밍");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.KEYWORD);
    }

    @Test
    void normalizeIsbn13Detected() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("9788936439743");

        assertThat(result.value()).isEqualTo("9788936439743");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
    }

    @Test
    void normalizeIsbn13WithHyphensCleaned() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("978-89-364-3974-3");

        assertThat(result.value()).isEqualTo("9788936439743");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
    }

    @Test
    void normalizeIsbn10ConvertedToIsbn13() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("0306406152");

        assertThat(result.value()).isEqualTo("9780306406157");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
    }

    @Test
    void normalizeIsbn10WithHyphensConvertedToIsbn13() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("0-306-40615-2");

        assertThat(result.value()).isEqualTo("9780306406157");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
    }

    @Test
    void normalizeIsbn10EndingWithXConvertedToIsbn13() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("0-8044-2957-x");

        assertThat(result.value()).isEqualTo("9780804429573");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
    }

    @Test
    void normalizeMultipleSpacesCollapsed() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("자바  의  정석");

        assertThat(result.value()).isEqualTo("자바 의 정석");
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.KEYWORD);
    }

    @Test
    void normalizeNullReturnsEmptyKeyword() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize(null);

        assertThat(result.value()).isEmpty();
        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.KEYWORD);
    }

    // F1: 카카오가 ISBN 자리에 주는 서점 내부 코드(2로 시작)를 ISBN으로 믿으면
    // 식별에 실패한 책이 "도서관 전부 미보유"로 보인다. 978/979 접두 + 체크섬으로 거른다
    @Test
    void normalizeThirteenDigitStoreCodeIsNonStandard() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("2004566000649");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.NON_STANDARD_ISBN);
        assertThat(result.value()).isEqualTo("2004566000649");
    }

    @Test
    void normalizeIsbn13WithWrongCheckDigitIsNonStandard() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("9788936439744");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.NON_STANDARD_ISBN);
    }

    @Test
    void isValidIsbn13ChecksPrefixAndCheckDigit() {
        assertThat(InputNormalizer.isValidIsbn13("9788936439743")).isTrue();
        assertThat(InputNormalizer.isValidIsbn13("9791168340510")).isTrue();
        assertThat(InputNormalizer.isValidIsbn13("2004566000649")).isFalse();
        assertThat(InputNormalizer.isValidIsbn13("9788936439744")).isFalse();
        assertThat(InputNormalizer.isValidIsbn13("978893643974")).isFalse();
        assertThat(InputNormalizer.isValidIsbn13(null)).isFalse();
    }

    // ISBN10은 978을 붙여 변환하면 체크섬이 새로 계산돼 항상 유효해 보인다 → 변환 전에 ISBN10 체크섬을 검사
    @Test
    void normalizeIsbn10WithWrongCheckDigitIsNonStandardBeforeConversion() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("8936434129");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.NON_STANDARD_ISBN);
        assertThat(result.value()).isEqualTo("8936434129");
    }

    @Test
    void normalizeIsbn10LowercaseXWithoutHyphensConverted() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("007462542x");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
        assertThat(result.value()).isEqualTo("9780074625422");
    }

    @Test
    void normalizeHyphenatedIsbn13WithWrongCheckDigitIsNonStandard() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("978-89-364-3974-4");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.NON_STANDARD_ISBN);
        assertThat(result.value()).isEqualTo("9788936439744");
    }

    @Test
    void normalizeIsbn13With979PrefixDetected() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("9791168340510");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.ISBN);
        assertThat(result.value()).isEqualTo("9791168340510");
    }

    @Test
    void normalizeXInMiddleIsKeyword() {
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("12345x7890");

        assertThat(result.type()).isEqualTo(InputNormalizer.QueryType.KEYWORD);
        assertThat(result.value()).isEqualTo("12345x7890");
    }
}
