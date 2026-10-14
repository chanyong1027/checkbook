package com.checkbook.client.yes24;

import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.client.yes24.dto.Yes24ItemDetailResponse;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * YES24 OpenAPI ISBN 조회. 재시도하지 않는다.
 * <ul>
 *   <li>찾음 → 검증된 {@link Yes24Book}, '책 없음'(404 GOODS_002만) → 빈 결과</li>
 *   <li>그 밖의 결과는 {@link Yes24Exception}: 타임아웃, 한도(초당·일일), 오류·응답 검증 실패</li>
 * </ul>
 * 우리 쪽 선제한(100ms당 1건)과 일일 중단은 이 클라이언트 경계에 둔다. 식별과 추천 배치가 같은 한도를 나눠 쓰기 때문이다.
 */
@Slf4j
public class Yes24Client {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String NOT_FOUND_CODE = "GOODS_002";
    private static final String DAILY_LIMIT_CODE = "RATE_002";
    // 해제 시각 헤더가 이보다 멀면(단위 오류 등) 믿지 않는다 — 일일 한도는 길어야 다음 자정에 풀린다
    private static final Duration MAX_TRUSTED_RESET = Duration.ofHours(48);
    private static final String USER_AGENT = "CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)";

    private final RestClient restClient;
    private final RateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration callDeadline;
    private final Yes24DailyStop dailyStop = new Yes24DailyStop();
    // 마지막 응답 시점의 관측치. 재시작 직후에는 값이 없다(NaN)
    private final AtomicReference<Double> remainingDay = new AtomicReference<>(Double.NaN);

    public Yes24Client(
            RestClient.Builder builder,
            Yes24Properties properties,
            RateLimiter rateLimiter,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        // JdkClientHttpRequestFactory의 readTimeout은 '읽기 간격'이 아니라 sendAsync부터 본문 끝까지의 전체 마감이다
        // (Spring 7 JdkClientHttpRequest.TimeoutHandler). connectTimeout은 그 안의 연결 단계 상한이다.
        // HTTP/1.1 고정: 실측·수집 스크립트와 같은 조건(JDK 기본값은 HTTP/2)
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.callDeadline = properties.readTimeout();
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.warn("YES24 API 키(YES24_KEY)가 비어 있습니다. 모든 식별이 실패(FAILED)하고 힌트로만 표시됩니다");
        }

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Api-Key", properties.apiKey() == null ? "" : properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestFactory(requestFactory)
                .build();
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;

        Gauge.builder("yes24.remaining.day", remainingDay, AtomicReference::get)
                .description("YES24 일일 호출 잔량 (마지막 응답 헤더 관측치)")
                .register(meterRegistry);
    }

    public Optional<Yes24Book> lookupByIsbn(String isbn13) {
        if (dailyStop.isActive(clock.instant())) {
            countRateLimited("self", "day");
            throw new Yes24Exception(Kind.DAILY_LIMIT, false, "YES24 일일 한도 소진으로 호출 중단 중 (해제 " + dailyStop.until() + ")");
        }
        if (!rateLimiter.acquirePermission()) {
            countRateLimited("self", "second");
            throw new Yes24Exception(Kind.RATE_LIMITED, false, "YES24 선제한 거절");
        }

        Instant sentAt = clock.instant();
        long startedNanos = System.nanoTime();
        RawResponse raw;
        try {
            // URI 템플릿을 유지해야 http.client.requests의 uri 태그가 ISBN 실값으로 늘어나지 않는다
            raw = restClient.get()
                    .uri("/v1/goods/itemDetail?searchType=ISBN13&query={isbn}&detail=N", isbn13)
                    .exchange((request, response) -> {
                        Yes24ItemDetailResponse body = null;
                        boolean parseFailed = false;
                        try {
                            body = response.bodyTo(Yes24ItemDetailResponse.class);
                        } catch (RestClientException e) {
                            parseFailed = true;
                        }
                        return new RawResponse(response.getStatusCode().value(), response.getHeaders(), body, parseFailed);
                    });
        } catch (RestClientException e) {
            if (isTimeout(e)) {
                throw new Yes24Exception(Kind.TIMEOUT, true, "YES24 타임아웃", e);
            }
            throw new Yes24Exception(Kind.FAILED, true, "YES24 호출 실패", e);
        }

        observeRemainingDay(raw.headers());

        // 200인데 본문을 읽는 중에 마감이 지나면 스트림이 닫혀 '파싱 실패'로 보인다 → 타임아웃으로 분류.
        // 200이 아닌 응답은 상태 코드·헤더만으로 판정하므로(예: HTML 429 + 일일 잔량 0) 덮어쓰지 않는다
        if (raw.status() == 200 && raw.parseFailed()
                && Duration.ofNanos(System.nanoTime() - startedNanos).compareTo(callDeadline) >= 0) {
            throw new Yes24Exception(Kind.TIMEOUT, true, "YES24 타임아웃(본문 수신 중)");
        }
        return classify(isbn13, raw, sentAt);
    }

    private Optional<Yes24Book> classify(String isbn13, RawResponse raw, Instant sentAt) {
        String errorCode = raw.body() != null ? raw.body().errorCode() : null;

        if (raw.status() == 200) {
            return Optional.of(validate(isbn13, raw));
        }
        if (raw.status() == 404 && NOT_FOUND_CODE.equals(errorCode)) {
            return Optional.empty();
        }
        if (raw.status() == 429) {
            // 본문이 JSON이 아니어도(게이트웨이 HTML 등) 일일 잔량 0이면 일일 한도로 본다
            if (DAILY_LIMIT_CODE.equals(errorCode) || isZero(raw.headers().getFirst("x-ratelimit-remaining-day"))) {
                Instant until = resetDay(raw.headers(), sentAt);
                dailyStop.stopUntil(until);
                countRateLimited("remote", "day");
                log.warn("YES24 일일 한도 소진(RATE_002): {}까지 호출 중단", until);
                throw new Yes24Exception(Kind.DAILY_LIMIT, true, "YES24 일일 한도 초과");
            }
            // RATE_001 또는 코드 없는 429는 초당 한도로 본다
            countRateLimited("remote", "second");
            throw new Yes24Exception(Kind.RATE_LIMITED, true, "YES24 초당 한도 초과");
        }
        throw new Yes24Exception(Kind.FAILED, true,
                "YES24 오류 응답: HTTP " + raw.status() + (errorCode != null ? " " + errorCode : ""));
    }

    // 200이어도 정상 결과를 확보하지 못했으면 실패다 (점검 페이지, success=false, 다른 책 등)
    private Yes24Book validate(String isbn13, RawResponse raw) {
        Yes24ItemDetailResponse body = raw.body();
        if (raw.parseFailed() || body == null || !Boolean.TRUE.equals(body.success()) || body.data() == null) {
            throw new Yes24Exception(Kind.FAILED, true, "YES24 응답 검증 실패: 본문 형식");
        }
        List<Yes24ItemDetailResponse.Item> items = body.data().items();
        if (items == null || items.isEmpty()) {
            throw new Yes24Exception(Kind.FAILED, true, "YES24 응답 검증 실패: 상품 없음");
        }
        Yes24ItemDetailResponse.Item item = items.get(0);
        if (item.isbn13() == null || !isbn13.equals(item.isbn13().trim())) {
            throw new Yes24Exception(Kind.FAILED, true, "YES24 응답 검증 실패: ISBN 불일치");
        }
        if (item.title() == null || item.title().isBlank()) {
            throw new Yes24Exception(Kind.FAILED, true, "YES24 응답 검증 실패: 제목 없음");
        }
        return new Yes24Book(
                item.title().strip(),
                item.author(),
                item.publisher(),
                isbn13,
                price(item.salePrice()),
                item.link(),
                item.cover());
    }

    private static Integer price(BigDecimal salePrice) {
        if (salePrice == null || salePrice.signum() <= 0) {
            return null;
        }
        return salePrice.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    // 해제 시각: x-ratelimit-reset-day(epoch 초) 우선, 없거나 믿을 수 없으면 '요청을 보낸 시각' 기준 다음 KST 00시.
    // 요청 시각 기준이어야 전날 보낸 요청의 429가 자정 뒤에 도착해도 새 날 전체를 막지 않는다
    private Instant resetDay(HttpHeaders headers, Instant sentAt) {
        Instant fallback = sentAt.atZone(KST).toLocalDate().plusDays(1).atStartOfDay(KST).toInstant();
        String header = headers.getFirst("x-ratelimit-reset-day");
        if (header == null) {
            return fallback;
        }
        try {
            Instant reset = Instant.ofEpochSecond(Long.parseLong(header.trim()));
            // 너무 멀면(단위 오류) 영구 중단, 과거면(시계 차이·'남은 초' 형식) 중단 없음 → 둘 다 믿지 않는다
            if (reset.isAfter(sentAt.plus(MAX_TRUSTED_RESET)) || !reset.isAfter(sentAt)) {
                log.warn("YES24 reset-day 헤더를 믿을 수 없습니다: {} → 다음 KST 자정 사용", header);
                return fallback;
            }
            return reset;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void observeRemainingDay(HttpHeaders headers) {
        String value = headers.getFirst("x-ratelimit-remaining-day");
        if (value == null) {
            return;
        }
        try {
            remainingDay.set(Double.parseDouble(value.trim()));
        } catch (NumberFormatException ignored) {
            // 관측치일 뿐이라 형식이 이상하면 이전 값을 유지한다
        }
    }

    private static boolean isZero(String value) {
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(value.trim()) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void countRateLimited(String by, String kind) {
        meterRegistry.counter("yes24.rate.limited", "by", by, "kind", kind).increment();
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record RawResponse(int status, HttpHeaders headers, Yes24ItemDetailResponse body, boolean parseFailed) {
    }
}
