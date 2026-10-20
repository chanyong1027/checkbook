package com.checkbook.search.service;

import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.MillieClientException;
import com.checkbook.client.millie.MillieProperties;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.MillieAvailability.Format;
import com.checkbook.search.service.MillieBookMatcher.MatchedMillie;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class MillieBookService {

    // 서킷 인스턴스 이름(설정 resilience4j.circuitbreaker.instances.millie, 지표·알림의 name 태그)
    static final String CIRCUIT_BREAKER_NAME = "millie";

    private final MillieClient millieClient;
    private final MillieBookMatcher matcher;
    private final BookMetadataNormalizer normalizer;
    private final MeterRegistry meterRegistry;
    private final MillieProperties properties;
    private final CircuitBreaker circuitBreaker;

    public MillieBookService(MillieClient millieClient, MillieBookMatcher matcher, BookMetadataNormalizer normalizer,
                             MeterRegistry meterRegistry, MillieProperties properties,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.millieClient = millieClient;
        this.matcher = matcher;
        this.normalizer = normalizer;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    // 밀리는 비공식 API라 막힐 수 있다(WAF 403) — 막힌 동안 계속 두드리지 않게 서킷으로 감싼다(T4 결정 카드 4).
    // 열리면 섹션은 지금 실패와 같은 FAILED·UNAVAILABLE. 메시지는 응답 failures[].reason으로 나가 라이브러리 원문 대신 우리 문구로
    private List<MillieBookItem> search(String keyword) {
        try {
            return circuitBreaker.executeSupplier(() -> millieClient.searchByTitle(keyword));
        } catch (CallNotPermittedException e) {
            throw new MillieClientException("밀리 일시 중단(서킷 열림)", e);
        }
    }

    /**
     * @param book         매칭 기준 책 정보(YES24 또는 힌트). 검색어도 이 제목으로 만든다
     * @param fallbackHint 주 정보로 매칭이 안 될 때 같은 후보 목록에서 한 번 더 맞춰 볼 힌트(nullable).
     *                     YES24가 부제 앞 문구가 빠진 짧은 제목을 주는 경우를 보완한다(결정 E8). 밀리를 다시 부르지 않는다
     */
    public MillieAvailability findAvailability(IdentifiedBook book, IdentifiedBook fallbackHint) {
        if (book == null
                || book.title() == null
                || book.title().isBlank()) {
            return MillieAvailability.unavailable();
        }

        // 풀타이틀(부제·판 정보 괄호 포함)로 검색하면 밀리에서 0건 나오는 경우가 잦아
        // 부제와 판 정보 괄호를 뗀 키워드로 검색. 정확도는 이후 matcher가 책임.
        String keyword = normalizer.searchKeyword(book.title());
        List<MillieBookItem> candidates = search(keyword);
        if (candidates.isEmpty()) {
            meterRegistry.counter("millie.match", "via", "no_candidates").increment();
            log.info("밀리 검색 결과 0건: keyword={}, title={}",
                    keyword, book.title());
            return MillieAvailability.unavailable();
        }

        String via = "primary";
        Optional<MatchedMillie> matched = matcher.findMatch(book, candidates);
        if (matched.isEmpty() && fallbackHint != null && fallbackHint.hasTitle()) {
            // 저자로 확인된 매칭만 받는다(제목만 일치하는 TITLE_ONLY 제외) — 힌트에는 ISBN 대응 보장이 없다
            matched = matcher.findMatch(titleFromHint(book, fallbackHint), candidates, false);
            via = "hint_fallback";
        }
        if (matched.isEmpty()) {
            meterRegistry.counter("millie.match", "via", "none").increment();
            log.info("밀리 매칭 실패: title={}, hintTitle={}, author={}, publisher={}, candidateCount={}",
                    book.title(),
                    fallbackHint != null ? fallbackHint.title() : null,
                    book.author(),
                    book.publisher(),
                    candidates.size());
            return MillieAvailability.unavailable();
        }
        // via=primary|hint_fallback은 '매칭됨'이다(이용 가능 여부는 아래에서 따로 판정)
        meterRegistry.counter("millie.match", "via", via).increment();
        if ("hint_fallback".equals(via)) {
            log.info("밀리 힌트 제목 보조 매칭: title={}, hintTitle={}, matched={}",
                    book.title(), fallbackHint.title(), matched.get().primary().contentName());
        }

        MillieBookItem primary = matched.get().primary();
        Format format = matched.get().format();

        if (!primary.isAvailable()) {
            // 매처 Comparator 1차 키가 정확도(키워드 없음)이므로, 정확한 본책이
            // coming_soon이면 derivative/alternative available 있어도 본책이 primary가 됨.
            // 본 분기는 정확도 우선 정책에 따른 의도된 unavailable 반환.
            return MillieAvailability.unavailable();
        }

        return new MillieAvailability(
                true,
                primary.bookSeq(),
                properties.detailUrlBase() + primary.bookSeq(),
                format
        );
    }

    // 힌트에서는 제목만 빌린다. 저자·출판사는 검증된 주 정보(YES24) 값을 쓰고, 비어 있을 때만 힌트 값을 쓴다 —
    // 클라이언트가 보낸 값이 저자 판정을 바꿔 다른 책이 '이용 가능'으로 보이지 않게
    private static IdentifiedBook titleFromHint(IdentifiedBook book, IdentifiedBook hint) {
        return new IdentifiedBook(
                hint.title(),
                book.author() != null ? book.author() : hint.author(),
                book.isbn13(),
                book.publisher() != null ? book.publisher() : hint.publisher(),
                null);
    }
}
