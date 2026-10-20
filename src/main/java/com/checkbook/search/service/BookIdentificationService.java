package com.checkbook.search.service;

import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Exception;
import com.checkbook.client.yes24.Yes24TransientException;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;
import com.checkbook.search.dto.IdentifiedBook;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * ISBN으로 책을 식별한다. YES24를 부르고, 찾지 못하거나 실패하면 프런트가 보낸 힌트로 대신한다.
 * 힌트가 없거나 무효하면 source=NONE. 무효한 힌트는 400을 내지 않고 없는 것으로 처리한다(선택 기능).
 * <p>
 * YES24 호출은 Retry(서킷(클라이언트))로 감싼다(T4 결정 카드 2·6, 설정 resilience4j.*.instances.yes24):
 * 일시 오류({@link Yes24TransientException})만 1회 재시도하고, 일시 오류가 쌓이면 서킷이 열려 부르지 않는다.
 * 식별은 fan-out 전 순차라 재시도 시간이 응답 시간에 그대로 더해진다(최악 약 0.7 + 0.2 + 0.7초).
 */
@Slf4j
@Service
public class BookIdentificationService {

    static final int MAX_TEXT_LENGTH = 200;
    static final int MAX_COVER_URL_LENGTH = 1000;

    // 서킷·재시도 인스턴스 이름(지표·알림의 name 태그). 식별 경로만 감싼다 — 추천 배치(categoryList)는 다음 cron이 있어 밖에 둔다
    static final String YES24_RESILIENCE_NAME = "yes24";

    private final Yes24Client yes24Client;
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public BookIdentificationService(Yes24Client yes24Client, MeterRegistry meterRegistry,
                                     CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.yes24Client = yes24Client;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(YES24_RESILIENCE_NAME);
        this.retry = retryRegistry.retry(YES24_RESILIENCE_NAME);
    }

    public IdentificationResult identify(String isbn13, BookHint hint) {
        Optional<IdentifiedBook> hintBook = validHint(isbn13, hint);

        Yes24Status status;
        boolean sent = true;
        try {
            Optional<Yes24Book> found = lookupWithResilience(isbn13);
            if (found.isPresent()) {
                return record(fromYes24(isbn13, found.get(), hintBook.orElse(null)));
            }
            status = Yes24Status.NOT_FOUND;
        } catch (CallNotPermittedException e) {
            // 서킷이 열려 YES24를 부르지 않았다. 새 책 섹션은 지금 YES24 오류와 같은 FAILED로 보인다(결정 카드 6-1)
            status = Yes24Status.FAILED;
            sent = false;
            log.info("YES24 식별 생략(서킷 열림): isbn={}", isbn13);
        } catch (Yes24Exception e) {
            status = switch (e.kind()) {
                case TIMEOUT -> Yes24Status.TIMEOUT;
                case RATE_LIMITED -> Yes24Status.RATE_LIMITED;
                case DAILY_LIMIT -> Yes24Status.DAILY_LIMIT;
                case FAILED -> Yes24Status.FAILED;
            };
            sent = e.sent();
            log.info("YES24 식별 실패: isbn={}, kind={}, sent={}, message={}{}", isbn13, e.kind(), sent, e.getMessage(),
                    e.getSuppressed().length > 0 ? " (재시도 못 보냄: " + e.getSuppressed()[0].getMessage() + ")" : "");
        } catch (RuntimeException e) {
            // 예상 못 한 예외도 검색 전체를 실패시키지 않는다. 요청이 나갔는지 알 수 없어 '보냄'으로 본다(FAILED)
            status = Yes24Status.FAILED;
            log.error("YES24 식별 중 예상 못 한 오류: isbn={}", isbn13, e);
        }
        return record(fallback(isbn13, hintBook, status, sent));
    }

    // 순서는 Retry(서킷(클라이언트 안 선제한·예산·호출)) — 결정 카드 규칙 4. 재시도도 시도마다 허가·예산을 다시 거친다(규칙 1)
    private Optional<Yes24Book> lookupWithResilience(String isbn13) {
        AtomicReference<Yes24TransientException> firstFailure = new AtomicReference<>();
        Supplier<Optional<Yes24Book>> attempt = () -> {
            try {
                return yes24Client.lookupByIsbn(isbn13);
            } catch (Yes24TransientException e) {
                firstFailure.compareAndSet(null, e);
                throw e;
            }
        };
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, attempt)).get();
        } catch (CallNotPermittedException | Yes24Exception e) {
            // 재시도를 보내지 못했다(그 사이 서킷이 열림, 허가·예산 없음) — 사용자에게는 실제로 난 첫 실패를 보인다
            Yes24TransientException first = firstFailure.get();
            if (first != null && notSent(e)) {
                first.addSuppressed(e);   // 재시도를 왜 못 보냈는지는 로그의 suppressed로 남긴다
                throw first;
            }
            throw e;
        }
    }

    private static boolean notSent(RuntimeException e) {
        return e instanceof CallNotPermittedException || e instanceof Yes24Exception y && !y.sent();
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
