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
        InputNormalizer.NormalizedQuery result = InputNormalizer.normalize("0-471-41549-x");

        assertThat(result.value()).isEqualTo("9780471415497");
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
}
