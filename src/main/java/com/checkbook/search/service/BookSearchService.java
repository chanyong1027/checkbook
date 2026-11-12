package com.checkbook.search.service;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.search.dto.BookCandidateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookSearchService {

    // 카카오 책 검색이 받는 page의 상한(카카오 개발자 문서: 1~50). 컨트롤러는 100까지 받는다
    static final int KAKAO_MAX_PAGE = 50;

    private final KakaoBookClient kakaoBookClient;

    public BookCandidateResponse searchCandidates(String query, int page, int size) {
        log.info("책 후보 검색: query={}, page={}, size={}", query, page, size);
        if (page > KAKAO_MAX_PAGE) {
            // 카카오가 거절할 쪽은 부르지 않고 '목록 끝'으로 답한다. 카카오 실패는 이제 503(장애)이라, 부르면 '더 보기'가
            // 장애 문구로 끝나고 외부 요청만으로 5xx 알림을 켤 수 있다(리뷰 지적, 결정 2026-11-10 카드 1)
            return new BookCandidateResponse(List.of(), new BookCandidateResponse.Pagination(page, size, 0, true));
        }

        KakaoBookResponse kakaoResponse = kakaoBookClient.searchBooks(query, page, size);

        List<BookCandidateResponse.BookCandidate> candidates = kakaoResponse.documents().stream()
                .map(this::toCandidate)
                .toList();

        BookCandidateResponse.Pagination pagination = new BookCandidateResponse.Pagination(
                page,
                size,
                Objects.requireNonNullElse(kakaoResponse.meta().totalCount(), 0),
                // 카카오가 is_end=false를 줘도 50쪽이 끝이다 — 여기서 끝을 알려야 프런트가 51쪽을 부르지 않고
                // 개수 표시와 '결과가 많아 여기까지' 안내가 유지된다(51쪽 응답의 totalCount 0이 덮어쓰지 않게)
                Boolean.TRUE.equals(kakaoResponse.meta().isEnd()) || page >= KAKAO_MAX_PAGE
        );

        return new BookCandidateResponse(candidates, pagination);
    }

    private BookCandidateResponse.BookCandidate toCandidate(KakaoBookResponse.Document document) {
        return new BookCandidateResponse.BookCandidate(
                document.title(),
                String.join(", ", document.authors()),
                document.publisher(),
                extractIsbn13(document.isbn()),
                document.thumbnail(),
                extractDate(document.datetime())
        );
    }

    private String extractIsbn13(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }

        return Arrays.stream(isbn.split("\\s+"))
                .filter(value -> value.length() == 13)
                .findFirst()
                .orElse(isbn.trim());
    }

    private String extractDate(String datetime) {
        if (datetime == null || datetime.length() < 10) {
            return null;
        }

        return datetime.substring(0, 10);
    }
}
