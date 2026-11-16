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

    // 이 API가 받는 page의 상한. 카카오 책 검색이 page 1~50만 받아서 그 값을 따른다(카카오 개발자 문서 '책 검색하기').
    // 컨트롤러의 @Max가 이 값을 쓰므로 넘는 쪽은 여기까지 오지 않는다 — 카카오 거절이 503(장애)이라 부르면
    // '더 보기' 51번째가 장애 문구가 되고 외부 요청만으로 5xx 알림을 켤 수 있다(결정 2026-11-10 카드 1)
    public static final int MAX_PAGE = 50;

    private final KakaoBookClient kakaoBookClient;

    public BookCandidateResponse searchCandidates(String query, int page, int size) {
        log.info("책 후보 검색: query={}, page={}, size={}", query, page, size);

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
                Boolean.TRUE.equals(kakaoResponse.meta().isEnd()) || page >= MAX_PAGE
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
