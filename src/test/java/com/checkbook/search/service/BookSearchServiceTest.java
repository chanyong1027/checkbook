package com.checkbook.search.service;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.search.dto.BookCandidateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSearchServiceTest {

    @Mock
    private KakaoBookClient kakaoBookClient;

    private BookSearchService bookSearchService;

    @BeforeEach
    void setUp() {
        bookSearchService = new BookSearchService(kakaoBookClient);
    }

    @Test
    void searchCandidatesReturnsMappedItems() {
        when(kakaoBookClient.searchBooks("혼모노", 1, 10))
                .thenReturn(new KakaoBookResponse(
                        new KakaoBookResponse.Meta(2, 2, true),
                        List.of(
                                new KakaoBookResponse.Document(
                                        "혼모노",
                                        List.of("성해나"),
                                        "창비",
                                        "https://cover.jpg",
                                        "9788936439743 8936439743",
                                        "2021-09-17T00:00:00.000+09:00"
                                ),
                                new KakaoBookResponse.Document(
                                        "혼모노 특별판",
                                        List.of("성해나"),
                                        "창비",
                                        "https://cover2.jpg",
                                        "9788936439744 8936439744",
                                        "2022-01-01T00:00:00.000+09:00"
                                )
                        )
                ));

        BookCandidateResponse response = bookSearchService.searchCandidates("혼모노", 1, 10);

        assertThat(response.items()).hasSize(2);

        BookCandidateResponse.BookCandidate first = response.items().get(0);
        assertThat(first.isbn13()).isEqualTo("9788936439743");
        assertThat(first.title()).isEqualTo("혼모노");
        assertThat(first.author()).isEqualTo("성해나");
        assertThat(first.publisher()).isEqualTo("창비");
        assertThat(first.coverUrl()).isEqualTo("https://cover.jpg");
        assertThat(first.publishedAt()).isEqualTo("2021-09-17");
    }

    @Test
    void searchCandidatesMultipleAuthorsJoinedByComma() {
        when(kakaoBookClient.searchBooks("클린코드", 1, 10))
                .thenReturn(new KakaoBookResponse(
                        new KakaoBookResponse.Meta(1, 1, true),
                        List.of(new KakaoBookResponse.Document(
                                "클린 코드",
                                List.of("로버트 C. 마틴", "정승환"),
                                "인사이트",
                                "https://cover.jpg",
                                "9788966260959 8966260950",
                                "2013-12-24T00:00:00.000+09:00"
                        ))
                ));

        BookCandidateResponse response = bookSearchService.searchCandidates("클린코드", 1, 10);

        assertThat(response.items().get(0).author()).isEqualTo("로버트 C. 마틴, 정승환");
    }

    @Test
    void searchCandidatesIsbnOnlyIsbn13NoIsbn10() {
        when(kakaoBookClient.searchBooks("q", 1, 10))
                .thenReturn(new KakaoBookResponse(
                        new KakaoBookResponse.Meta(1, 1, true),
                        List.of(new KakaoBookResponse.Document(
                                "책",
                                List.of("저자"),
                                "출판사",
                                "https://cover.jpg",
                                "9788936439743",
                                "2020-01-01T00:00:00.000+09:00"
                        ))
                ));

        BookCandidateResponse response = bookSearchService.searchCandidates("q", 1, 10);

        assertThat(response.items().get(0).isbn13()).isEqualTo("9788936439743");
    }

    @Test
    void searchCandidatesWhenKakaoReturnsEmptyReturnsEmptyWithPagination() {
        when(kakaoBookClient.searchBooks("없는책xyz", 1, 10))
                .thenReturn(new KakaoBookResponse(
                        new KakaoBookResponse.Meta(0, 0, true),
                        List.of()
                ));

        BookCandidateResponse response = bookSearchService.searchCandidates("없는책xyz", 1, 10);

        assertThat(response.items()).isEmpty();
        assertThat(response.pagination().totalCount()).isEqualTo(0);
        assertThat(response.pagination().isEnd()).isTrue();
    }

    @Test
    void searchCandidatesPaginationMetaIsMapped() {
        when(kakaoBookClient.searchBooks("자바", 2, 10))
                .thenReturn(new KakaoBookResponse(
                        new KakaoBookResponse.Meta(35, 35, false),
                        List.of()
                ));

        BookCandidateResponse response = bookSearchService.searchCandidates("자바", 2, 10);

        assertThat(response.pagination().page()).isEqualTo(2);
        assertThat(response.pagination().size()).isEqualTo(10);
        assertThat(response.pagination().totalCount()).isEqualTo(35);
        assertThat(response.pagination().isEnd()).isFalse();
    }
}
