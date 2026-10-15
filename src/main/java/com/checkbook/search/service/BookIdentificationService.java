package com.checkbook.search.service;

import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Exception;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;
import com.checkbook.search.dto.IdentifiedBook;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * ISBN으로 책을 식별한다. YES24를 한 번 부르고(재시도 없음), 찾지 못하거나 실패하면 프런트가 보낸 힌트로 대신한다.
 * 힌트가 없거나 무효하면 source=NONE. 무효한 힌트는 400을 내지 않고 없는 것으로 처리한다(선택 기능).
 */
@Slf4j
@Service
public class BookIdentificationService {

    static final int MAX_TEXT_LENGTH = 200;
    static final int MAX_COVER_URL_LENGTH = 1000;

    private final Yes24Client yes24Client;
    private final MeterRegistry meterRegistry;

    public BookIdentificationService(Yes24Client yes24Client, MeterRegistry meterRegistry) {
        this.yes24Client = yes24Client;
        this.meterRegistry = meterRegistry;
    }

    public IdentificationResult identify(String isbn13, BookHint hint) {
        Optional<IdentifiedBook> hintBook = validHint(isbn13, hint);

        Yes24Status status;
        boolean sent = true;
        try {
            Optional<Yes24Book> found = yes24Client.lookupByIsbn(isbn13);
            if (found.isPresent()) {
                return record(fromYes24(isbn13, found.get(), hintBook.orElse(null)));
            }
            status = Yes24Status.NOT_FOUND;
        } catch (Yes24Exception e) {
            status = switch (e.kind()) {
                case TIMEOUT -> Yes24Status.TIMEOUT;
                case RATE_LIMITED -> Yes24Status.RATE_LIMITED;
                case DAILY_LIMIT -> Yes24Status.DAILY_LIMIT;
                case FAILED -> Yes24Status.FAILED;
            };
            sent = e.sent();
            log.info("YES24 식별 실패: isbn={}, kind={}, sent={}, message={}", isbn13, e.kind(), sent, e.getMessage());
        } catch (RuntimeException e) {
            // 예상 못 한 예외도 검색 전체를 실패시키지 않는다. 요청이 나갔는지 알 수 없어 '보냄'으로 본다(FAILED)
            status = Yes24Status.FAILED;
            log.error("YES24 식별 중 예상 못 한 오류: isbn={}", isbn13, e);
        }
        return record(fallback(isbn13, hintBook, status, sent));
    }

    /**
     * 비표준 코드(978/979·체크섬 불일치)는 ISBN 기반 조회를 하지 않는다. 힌트가 있으면 표시·밀리에 쓴다.
     */
    public IdentificationResult identifyWithoutLookup(String code, BookHint hint) {
        return record(fallback(code, validHint(code, hint), Yes24Status.SKIPPED, false));
    }

    private static IdentificationResult fromYes24(String isbn13, Yes24Book book, IdentifiedBook hintBook) {
        return new IdentificationResult(
                Source.YES24,
                Yes24Status.FOUND,
                true,
                new IdentifiedBook(book.title(), blankToNull(book.author()), isbn13, blankToNull(book.publisher()),
                        httpUrlOrNull(book.coverUrl())),
                book.salePrice(),
                httpUrlOrNull(book.link()),
                hintBook);
    }

    private static IdentificationResult fallback(
            String isbn13, Optional<IdentifiedBook> hintBook, Yes24Status status, boolean sent) {
        return hintBook
                .map(book -> new IdentificationResult(Source.HINT, status, sent, book, null, null))
                .orElseGet(() -> new IdentificationResult(
                        Source.NONE, status, sent, new IdentifiedBook(null, null, isbn13, null, null), null, null));
    }

    // 힌트 유효성은 YES24 결과와 무관하게 먼저 판단한다 — 무효 힌트 메트릭은 YES24가 찾은 경우에도 오른다(프런트 결함 탐지용)
    private Optional<IdentifiedBook> validHint(String isbn13, BookHint hint) {
        if (hint == null) {
            return Optional.empty();
        }
        String title = blankToNull(hint.title());
        String author = blankToNull(hint.author());
        String publisher = blankToNull(hint.publisher());
        String coverUrl = blankToNull(hint.coverUrl());
        if (title == null && author == null && publisher == null && coverUrl == null) {
            return Optional.empty();   // 힌트를 보내지 않은 것과 같다
        }
        // 계약: 제목 없음·길이 초과면 힌트 전체가 무효
        boolean valid = title != null
                && title.length() <= MAX_TEXT_LENGTH
                && (author == null || author.length() <= MAX_TEXT_LENGTH)
                && (publisher == null || publisher.length() <= MAX_TEXT_LENGTH)
                && (coverUrl == null || coverUrl.length() <= MAX_COVER_URL_LENGTH);
        if (!valid) {
            meterRegistry.counter("search.hint.invalid").increment();
            log.info("무효한 힌트 무시: isbn={}", isbn13);
            return Optional.empty();
        }
        // http(s)가 아닌 표지는 표지만 버린다(제목·저자는 밀리 매칭에 여전히 유효)
        return Optional.of(new IdentifiedBook(title, author, isbn13, publisher, httpUrlOrNull(coverUrl)));
    }

    private IdentificationResult record(IdentificationResult result) {
        meterRegistry.counter("book.identification",
                "source", result.source().name(),
                "yes24", result.yes24().name()).increment();
        return result;
    }

    // 표지·링크는 <img src>, <a href>로 그대로 쓰이므로 http(s)만 허용한다
    private static String httpUrlOrNull(String url) {
        if (url == null) {
            return null;
        }
        String stripped = url.strip();
        String lower = stripped.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? stripped : null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        // strip: 한글 IME 전각 공백(U+3000)까지 제거해 IdentifiedBook.hasTitle()(isBlank)과 판정을 맞춘다
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
