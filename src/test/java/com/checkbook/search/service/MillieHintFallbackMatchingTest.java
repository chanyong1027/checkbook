package com.checkbook.search.service;

import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.search.dto.MillieAvailability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 매처·정규화기로 확인하는 회귀 사례(합성 데이터). 10-15 밀리 매칭 회귀(계약 8절 4번)에서
 * YES24가 부제 앞 문구가 빠진 짧은 제목을 줘서 밀리에 있는 책이 '없음'으로 바뀐 사례(1/88)를 고정한다.
 */
class MillieHintFallbackMatchingTest {

    private final BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
    private final MillieBookMatcher matcher = new MillieBookMatcher(new BookMatcher(normalizer), normalizer);
    private final MillieClient millieClient = mock(MillieClient.class);
    private final MillieBookService service = new MillieBookService(millieClient, matcher, normalizer, new SimpleMeterRegistry());

    private final MillieBookItem onMillie = new MillieBookItem(
            "SEQ9", "모두가 부자 되는 투자 공부", "", "홍길동", "경제경영", "테스트출판", true, true);

    @Test
    void shortYes24TitleAloneDoesNotMatch() {
        when(millieClient.searchByTitle("투자 공부")).thenReturn(List.of(onMillie));

        MillieAvailability result = service.findAvailability(book("투자 공부"), null);

        assertThat(result.available()).isFalse();
    }

    @Test
    void shortYes24TitleFallsBackToHintTitleOnSameCandidates() {
        when(millieClient.searchByTitle("투자 공부")).thenReturn(List.of(onMillie));

        MillieAvailability result = service.findAvailability(book("투자 공부"), book("모두가 부자 되는 투자 공부"));

        assertThat(result.available()).isTrue();
        assertThat(result.bookSeq()).isEqualTo("SEQ9");
    }

    // 힌트 저자가 달라도(조작·오류) 검증된 YES24 저자로 판정한다 — 저자가 다른 후보는 힌트 제목으로도 맞지 않는다
    @Test
    void hintCannotSwitchToCandidateByAnotherAuthor() {
        MillieBookItem otherAuthor = new MillieBookItem(
                "SEQ7", "모두가 부자 되는 투자 공부", "", "김철수", "경제경영", "다른출판", true, true);
        when(millieClient.searchByTitle("투자 공부")).thenReturn(List.of(otherAuthor));
        IdentifiedBook hint = new IdentifiedBook("모두가 부자 되는 투자 공부", "김철수", "9788900000009", "다른출판", null);

        MillieAvailability result = service.findAvailability(book("투자 공부"), hint);

        assertThat(result.available()).isFalse();
    }

    private static IdentifiedBook book(String title) {
        return new IdentifiedBook(title, "홍길동 저", "9788900000009", "테스트출판", null);
    }
}
