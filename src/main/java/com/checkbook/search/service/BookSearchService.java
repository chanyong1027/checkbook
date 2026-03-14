package com.checkbook.search.service;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.search.dto.BookCandidateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookSearchService {

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
                kakaoResponse.meta().totalCount(),
                kakaoResponse.meta().isEnd()
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
