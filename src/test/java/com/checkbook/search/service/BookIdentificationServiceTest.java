package com.checkbook.search.service;

import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Exception;
import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;
import com.checkbook.search.dto.IdentifiedBook;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookIdentificationServiceTest {

    private static final String ISBN = "9788900000009";
    private static final Yes24Book YES24_BOOK = new Yes24Book(
            "YES24 제목", "홍길동 저/김번역 역", "YES24출판", ISBN, 13500,
            "https://www.yes24.com/product/goods/1001", "https://image.yes24.com/goods/1001/XL");
    private static final BookHint VALID_HINT = new BookHint(
            "힌트 제목", "힌트 저자", "힌트출판", "https://search1.kakaocdn.net/thumb/1.jpg");
    private static final BookHint INVALID_HINT = new BookHint(" ", "힌트 저자", null, null);

    private Yes24Client yes24Client;
    private SimpleMeterRegistry meterRegistry;
    private BookIdentificationService service;

    @BeforeEach
    void setUp() {
        yes24Client = mock(Yes24Client.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new BookIdentificationService(yes24Client, meterRegistry);
    }

    // ---- YES24 결과 9가지(7종 + 한도의 우리 쪽/YES24 구분 + 예상 못 한 예외) × 힌트 {유효, 없음, 무효} ----

    enum HintCase {
        VALID(VALID_HINT), NONE(null), INVALID(INVALID_HINT);

        final BookHint hint;

        HintCase(BookHint hint) {
            this.hint = hint;
        }
    }

    static Stream<Arguments> yes24Outcomes() {
        return Stream.of(
                Arguments.of("FOUND", (Supplier<Object>) () -> Optional.of(YES24_BOOK), Yes24Status.FOUND, true),
                Arguments.of("NOT_FOUND", (Supplier<Object>) Optional::empty, Yes24Status.NOT_FOUND, true),
                Arguments.of("TIMEOUT", ex(Kind.TIMEOUT, true), Yes24Status.TIMEOUT, true),
                Arguments.of("RATE_LIMITED(self)", ex(Kind.RATE_LIMITED, false), Yes24Status.RATE_LIMITED, false),
                Arguments.of("RATE_LIMITED(remote)", ex(Kind.RATE_LIMITED, true), Yes24Status.RATE_LIMITED, true),
                Arguments.of("DAILY_LIMIT(self)", ex(Kind.DAILY_LIMIT, false), Yes24Status.DAILY_LIMIT, false),
                Arguments.of("DAILY_LIMIT(remote)", ex(Kind.DAILY_LIMIT, true), Yes24Status.DAILY_LIMIT, true),
                Arguments.of("FAILED", ex(Kind.FAILED, true), Yes24Status.FAILED, true),
                // 클라이언트의 예상 못 한 예외도 검색 전체를 500으로 만들지 않고 FAILED로 흡수
                Arguments.of("UNEXPECTED", (Supplier<Object>) () -> { throw new IllegalStateException("bug"); },
                        Yes24Status.FAILED, true)
        );
    }

    static Stream<Arguments> matrix() {
        return yes24Outcomes().flatMap(outcome -> Stream.of(HintCase.values())
                .map(hint -> Arguments.of(outcome.get()[0], outcome.get()[1], outcome.get()[2], outcome.get()[3], hint)));
    }

    @ParameterizedTest(name = "[{index}] yes24={0}, hint={4}")
    @MethodSource("matrix")
    @SuppressWarnings("unchecked")
    void identifyMatrix(String label, Supplier<Object> yes24, Yes24Status expectedYes24, boolean expectedSent, HintCase hintCase) {
        when(yes24Client.lookupByIsbn(ISBN)).thenAnswer(invocation -> yes24.get());

        IdentificationResult result = service.identify(ISBN, hintCase.hint);

        assertThat(result.yes24()).isEqualTo(expectedYes24);
        assertThat(result.yes24Sent()).isEqualTo(expectedSent);
        verify(yes24Client, times(1)).lookupByIsbn(ISBN);   // 재시도 없음

        if (expectedYes24 == Yes24Status.FOUND) {
            // 힌트 유무와 무관하게 YES24 값
            assertThat(result.source()).isEqualTo(Source.YES24);
            assertThat(result.book()).isEqualTo(new IdentifiedBook(
                    "YES24 제목", "홍길동 저/김번역 역", ISBN, "YES24출판", "https://image.yes24.com/goods/1001/XL"));
            assertThat(result.price()).isEqualTo(13500);
            assertThat(result.productUrl()).isEqualTo("https://www.yes24.com/product/goods/1001");
        } else if (hintCase == HintCase.VALID) {
            assertThat(result.source()).isEqualTo(Source.HINT);
            assertThat(result.book()).isEqualTo(new IdentifiedBook(
                    "힌트 제목", "힌트 저자", ISBN, "힌트출판", "https://search1.kakaocdn.net/thumb/1.jpg"));
            // 힌트는 가격·링크에 쓰지 않는다
            assertThat(result.price()).isNull();
            assertThat(result.productUrl()).isNull();
        } else {
            assertThat(result.source()).isEqualTo(Source.NONE);
            assertThat(result.book()).isEqualTo(new IdentifiedBook(null, null, ISBN, null, null));
            assertThat(result.price()).isNull();
            assertThat(result.productUrl()).isNull();
        }

        assertThat(meterRegistry.find("book.identification")
                .tag("source", result.source().name()).tag("yes24", expectedYes24.name())
                .counter().count()).isEqualTo(1.0);
        assertThat(hintInvalidCount()).isEqualTo(hintCase == HintCase.INVALID ? 1.0 : 0.0);
    }

    // ---- 비표준 코드: YES24를 부르지 않는다 ----

    @Test
    void nonStandardCodeWithValidHintUsesHintWithoutCallingYes24() {
        IdentificationResult result = service.identifyWithoutLookup("2004566000649", VALID_HINT);

        assertThat(result.yes24()).isEqualTo(Yes24Status.SKIPPED);
        assertThat(result.yes24Sent()).isFalse();
        assertThat(result.source()).isEqualTo(Source.HINT);
        assertThat(result.book().isbn13()).isEqualTo("2004566000649");
        assertThat(result.book().title()).isEqualTo("힌트 제목");
        verifyNoInteractions(yes24Client);
    }

    @Test
    void nonStandardCodeWithoutHintIsNone() {
        IdentificationResult result = service.identifyWithoutLookup("2004566000649", null);

        assertThat(result.yes24()).isEqualTo(Yes24Status.SKIPPED);
        assertThat(result.source()).isEqualTo(Source.NONE);
        assertThat(result.book()).isEqualTo(new IdentifiedBook(null, null, "2004566000649", null, null));
        verifyNoInteractions(yes24Client);
    }

    // ---- 힌트 유효성 ----

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"title-blank", "title-fullwidth-space", "title-too-long", "author-too-long",
            "publisher-too-long", "cover-too-long"})
    void invalidHintIsTreatedAsAbsent(String reason) {
        String longText = "가".repeat(201);
        BookHint hint = switch (reason) {
            case "title-blank" -> new BookHint("  ", "저자", "출판", null);
            case "title-too-long" -> new BookHint(longText, "저자", "출판", null);
            case "author-too-long" -> new BookHint("제목", longText, "출판", null);
            case "publisher-too-long" -> new BookHint("제목", "저자", longText, null);
            case "cover-too-long" -> new BookHint("제목", "저자", "출판", "https://x/" + "a".repeat(1000));
            case "title-fullwidth-space" -> new BookHint("\u3000", "저자", "출판", null);
            default -> throw new IllegalArgumentException(reason);
        };
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.empty());

        IdentificationResult result = service.identify(ISBN, hint);

        assertThat(result.source()).isEqualTo(Source.NONE);
        assertThat(hintInvalidCount()).isEqualTo(1.0);
    }

    @Test
    void hintValuesAreTrimmedAndBlankOptionalFieldsBecomeNull() {
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.empty());

        IdentificationResult result = service.identify(ISBN, new BookHint("  제목  ", " ", "", "  "));

        assertThat(result.source()).isEqualTo(Source.HINT);
        assertThat(result.book()).isEqualTo(new IdentifiedBook("제목", null, ISBN, null, null));
    }

    // 계약상 무효 힌트는 '제목 없음·길이 초과'뿐. 표지 형식이 이상하면 표지만 버린다
    @Test
    void nonHttpHintCoverIsDroppedButHintStaysValid() {
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.empty());

        IdentificationResult result = service.identify(ISBN, new BookHint("제목", "저자", "출판", "javascript:alert(1)"));

        assertThat(result.source()).isEqualTo(Source.HINT);
        assertThat(result.book()).isEqualTo(new IdentifiedBook("제목", "저자", ISBN, "출판", null));
        assertThat(hintInvalidCount()).isEqualTo(0.0);
    }

    @Test
    void yes24CoverAndTextFieldsAreSanitized() {
        Yes24Book raw = new Yes24Book("제목", " 저자 ", "  ", ISBN, 10000,
                "https://www.yes24.com/product/goods/1", "//evil.example/x.jpg");
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.of(raw));

        IdentificationResult result = service.identify(ISBN, null);

        assertThat(result.book()).isEqualTo(new IdentifiedBook("제목", "저자", ISBN, null, null));
    }

    @Test
    void allBlankHintIsAbsentNotInvalid() {
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.empty());

        IdentificationResult result = service.identify(ISBN, new BookHint(null, null, null, null));

        assertThat(result.source()).isEqualTo(Source.NONE);
        assertThat(hintInvalidCount()).isEqualTo(0.0);
    }

    // YES24 링크가 http(s)가 아니면 쓰지 않는다 (가격만 있고 링크가 없으면 새 책 칸은 링크 없이 판단 — SearchService 몫)
    @Test
    void nonHttpYes24LinkIsDropped() {
        Yes24Book weird = new Yes24Book("제목", "저자", "출판", ISBN, 10000, "javascript:alert(1)", null);
        when(yes24Client.lookupByIsbn(ISBN)).thenReturn(Optional.of(weird));

        IdentificationResult result = service.identify(ISBN, null);

        assertThat(result.price()).isEqualTo(10000);
        assertThat(result.productUrl()).isNull();
    }

    private double hintInvalidCount() {
        var counter = meterRegistry.find("search.hint.invalid").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static Supplier<Object> ex(Kind kind, boolean sent) {
        return () -> {
            throw new Yes24Exception(kind, sent, "test");
        };
    }
}
