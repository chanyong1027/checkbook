package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.MillieAvailability.Format;
import com.checkbook.search.service.MillieBookMatcher.MatchedMillie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MillieBookService {

    private static final String DETAIL_URL_BASE = "https://www.millie.co.kr/v3/book/";

    private final MillieClient millieClient;
    private final MillieBookMatcher matcher;

    public MillieAvailability findAvailability(AladinSearchResult aladinResult) {
        if (aladinResult == null
                || aladinResult.title() == null
                || aladinResult.title().isBlank()) {
            return MillieAvailability.unavailable();
        }

        List<MillieBookItem> candidates = millieClient.searchByTitle(aladinResult.title());
        if (candidates.isEmpty()) {
            return MillieAvailability.unavailable();
        }

        Optional<MatchedMillie> matched = matcher.findMatch(aladinResult, candidates);
        if (matched.isEmpty()) {
            log.info("밀리 매칭 실패: title={}, author={}, publisher={}, candidateCount={}",
                    aladinResult.title(),
                    aladinResult.author(),
                    aladinResult.publisher(),
                    candidates.size());
            return MillieAvailability.unavailable();
        }

        MillieBookItem primary = matched.get().primary();
        Format format = matched.get().format();

        if (!(primary.isService() && primary.isEbookRent())) {
            // 매처 Comparator 1차 키가 정확도(키워드 없음)이므로, 정확한 본책이
            // coming_soon이면 derivative/alternative available 있어도 본책이 primary가 됨.
            // 본 분기는 정확도 우선 정책에 따른 의도된 unavailable 반환.
            return MillieAvailability.unavailable();
        }

        return new MillieAvailability(
                true,
                primary.bookSeq(),
                DETAIL_URL_BASE + primary.bookSeq(),
                format
        );
    }
}
