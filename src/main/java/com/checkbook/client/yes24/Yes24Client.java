package com.checkbook.client.yes24;

import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.client.yes24.dto.Yes24CategoryListResponse;
import com.checkbook.client.yes24.dto.Yes24ItemDetailResponse;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * YES24 OpenAPI: ISBN 조회(식별)와 카테고리 목록 조회(메인 추천 배치). 클라이언트는 재시도하지 않는다 —
 * 식별 경로의 재시도·서킷은 {@code BookIdentificationService}가 이 호출을 감싸서 건다(시도마다 아래 선제한·예산을 다시 거친다).
 * 다시 보내면 나을 수 있는 실패(타임아웃·연결 실패·5xx)는 {@link Yes24TransientException}으로 던진다.
 * <ul>
 *   <li>ISBN 조회: 찾음 → 검증된 {@link Yes24Book}, '책 없음'(404 GOODS_002만) → 빈 결과</li>
 *   <li>목록 조회: 항목 목록, '결과 없음'(404 BEST_001·ATTN_001) → 빈 목록</li>
 *   <li>그 밖의 결과는 {@link Yes24Exception}: 타임아웃, 한도(초당·일일), 오류·응답 검증 실패</li>
 * </ul>
 * 우리 쪽 선제한(100ms당 1건)과 일일 중단은 이 클라이언트 경계에 둔다. 식별과 추천 배치가 같은 한도를 나눠 쓰기 때문이다.
 * 식별은 허가를 기다리지 않고, 배치(목록)만 최대 {@link #LIST_PERMIT_MAX_WAIT} 기다린다(PR-3 결정 카드 3).
 */
@Slf4j
public class Yes24Client {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String NOT_FOUND_CODE = "GOODS_002";
    private static final String DAILY_LIMIT_CODE = "RATE_002";
    // 해제 시각 헤더가 이보다 멀면(단위 오류 등) 믿지 않는다 — 일일 한도는 길어야 다음 자정에 풀린다
    private static final Duration MAX_TRUSTED_RESET = Duration.ofHours(48);
    private static final String USER_AGENT = "CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)";
    // 배치가 식별 요청과 같은 100ms 주기에 걸려 거절되면 섹션이 다음 cron까지(최대 7일) 지난 목록으로 남는다
    private static final Duration LIST_PERMIT_MAX_WAIT = Duration.ofSeconds(1);
    private static final long LIST_PERMIT_POLL_MILLIS = 100;

    private final RestClient restClient;
    private final RestClient listRestClient;
    private final RateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ExternalCallBudget budget;
    private final Duration callDeadline;
    private final Duration listCallDeadline;
    private final Yes24DailyStop dailyStop = new Yes24DailyStop();
    // 마지막 응답 시점의 관측치. 재시작 직후에는 값이 없다(NaN)
    private final AtomicReference<Double> remainingDay = new AtomicReference<>(Double.NaN);

    public Yes24Client(
            RestClient.Builder builder,
            Yes24Properties properties,
            RateLimiter rateLimiter,
            MeterRegistry meterRegistry,
            Clock clock,
            ExternalCallBudget budget
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
        // 목록은 배치 전용이라 사용자 응답 예산과 무관하다. 연결(HttpClient)은 식별과 같이 쓰고 전체 마감만 다르게 둔다
        JdkClientHttpRequestFactory listRequestFactory = new JdkClientHttpRequestFactory(httpClient);
        listRequestFactory.setReadTimeout(properties.listReadTimeout());
        this.listCallDeadline = properties.listReadTimeout();

        RestClient.Builder common = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Api-Key", properties.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT);
        this.listRestClient = common.clone().requestFactory(listRequestFactory).build();
        this.restClient = common.requestFactory(requestFactory).build();
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.budget = budget;

        Gauge.builder("yes24.remaining.day", remainingDay, AtomicReference::get)
                .description("YES24 일일 호출 잔량 (마지막 응답 헤더 관측치)")
                .register(meterRegistry);
        // countRateLimited가 세는 조합을 기동 때 0으로 미리 만든다. RATE_002처럼 드문 사건이 처음 나는 순간에 생기면
        // 첫 값이 1이라 increase()가 그 사건을 못 센다(알림이 첫 사건을 놓침)
        for (String kind : List.of("day", "budget", "second")) {
            rateLimitedCounter("self", kind);
        }
        for (String kind : List.of("day", "second")) {
            rateLimitedCounter("remote", kind);
        }
    }

    public Optional<Yes24Book> lookupByIsbn(String isbn13) {
        checkDailyStop();
        // 예산이 이미 다 찼으면 초당 허가를 쓰기 전에 끝낸다 — 허가를 쓰면 배치 몫(차단선~한도)을 쓰는 categoryList가 밀린다
        if (budget.isExhausted(ExternalProvider.YES24)) {
            throw budgetExhausted();
        }
        if (!rateLimiter.acquirePermission()) {
            throw selfRateLimited();
        }
        acquireBudget();

        Instant sentAt = clock.instant();
        // URI 템플릿을 유지해야 http.client.requests의 uri 태그가 ISBN 실값으로 늘어나지 않는다
        RawResponse<Yes24ItemDetailResponse> raw = exchange(restClient, callDeadline, Yes24ItemDetailResponse.class,
                "/v1/goods/itemDetail?searchType=ISBN13&query={isbn}&detail=N", isbn13);
        return classify(isbn13, raw, sentAt);
    }

    /**
     * 메인 추천 배치용 카테고리 목록. 식별과 같은 선제한·일일 중단을 쓰되 허가는 최대 1초 기다린다.
     * 항목 단위 검증은 하지 않는다(ISBN 없는 굿즈가 섞여 온다) — 호출자가 거른다.
     */
    public List<Yes24CategoryListResponse.Item> categoryList(Yes24CategoryList list, String categoryId, int pageSize) {
        checkDailyStop();
        acquireForList();
        // 기다리는 동안 이미 나간 식별 요청이 RATE_002를 받았을 수 있다 — 허가를 얻었어도 보내지 않는다
        checkDailyStop();
        // 배치는 예산 차단선을 넘어도 보낸다(차단선과 실제 한도 사이가 배치 몫). 세기만 한다
        budget.record(ExternalProvider.YES24);

        Instant sentAt = clock.instant();
        RawResponse<Yes24CategoryListResponse> raw = exchange(listRestClient, listCallDeadline,
                Yes24CategoryListResponse.class,
                // detail은 보내지 않는다(문서상 기본 N). 10-15 실측 표본과 같은 요청
                "/v1/category/{list}?categoryId={categoryId}&pageSize={pageSize}",
                list.path(), categoryId, pageSize);

        if (raw.status() == 200) {
            Yes24CategoryListResponse body = raw.body();
            if (raw.parseFailed() || body == null || !Boolean.TRUE.equals(body.success())
                    || body.data() == null || body.data().items() == null) {
                throw new Yes24Exception(Kind.FAILED, true, "YES24 목록 응답 검증 실패: 본문 형식 (" + list + ")");
            }
            return body.data().items();
        }
        String errorCode = raw.body() != null ? raw.body().errorCode() : null;
        if (raw.status() == 404 && list.noResultCode().equals(errorCode)) {
            return List.of();
        }
        throw rateLimitedOrFailed(raw.status(), raw.headers(), errorCode, sentAt);
    }

    private void checkDailyStop() {
        if (dailyStop.isActive(clock.instant())) {
            countRateLimited("self", "day");
            throw new Yes24Exception(Kind.DAILY_LIMIT, false, "YES24 일일 한도 소진으로 호출 중단 중 (해제 " + dailyStop.until() + ")");
        }
    }

    // 하루 예산 차단선이면 보내지 않는다. 섹션 의미는 일일 중단과 같다(SKIPPED, DAILY_LIMIT)
    // 위의 isExhausted 확인과 허가 사이에 다른 요청이 마지막 몫을 가져갈 수 있어 여기서 한 번 더 원자적으로 확인한다
    private void acquireBudget() {
        try {
            budget.acquire(ExternalProvider.YES24);
        } catch (DailyBudgetExceededException e) {
            throw budgetExhausted();
        }
    }

    private Yes24Exception budgetExhausted() {
        countRateLimited("self", "budget");
        return new Yes24Exception(Kind.DAILY_LIMIT, false, "YES24 하루 예산 차단선 도달로 호출하지 않음");
    }

    private Yes24Exception selfRateLimited() {
        countRateLimited("self", "second");
        return new Yes24Exception(Kind.RATE_LIMITED, false, "YES24 선제한 거절");
    }

    // 같은 제한기에서 100ms 간격으로 허가를 다시 시도한다. 식별 경로의 '대기 0'은 그대로 둔다
    private void acquireForList() {
        long deadlineNanos = System.nanoTime() + LIST_PERMIT_MAX_WAIT.toNanos();
        while (!rateLimiter.acquirePermission()) {
            if (System.nanoTime() >= deadlineNanos) {
                throw selfRateLimited();
            }
            try {
                TimeUnit.MILLISECONDS.sleep(LIST_PERMIT_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw selfRateLimited();
            }
        }
    }

    private <T> RawResponse<T> exchange(RestClient client, Duration deadline, Class<T> bodyType,
                                        String uriTemplate, Object... uriVariables) {
        long startedNanos = System.nanoTime();
        Instant sentAt = clock.instant();
        RawResponse<T> raw;
        try {
            raw = client.get()
                    .uri(uriTemplate, uriVariables)
                    .exchange((request, response) -> {
                        T body = null;
                        boolean parseFailed = false;
                        IOException transportFailure = null;
                        try {
                            body = response.bodyTo(bodyType);
                        } catch (RestClientException e) {
                            parseFailed = true;
                            // 연결 끊김·리셋이면 원인 사슬에 IOException이 있다. JSON 모양이 틀린 경우(Jackson 3 예외)에는 없다
                            transportFailure = ioCause(e);
                        }
                        return new RawResponse<>(response.getStatusCode().value(), response.getHeaders(), body, parseFailed,
                                transportFailure);
                    });
        } catch (RestClientException e) {
            // 응답을 못 받은 실패(연결 거절·끊김·타임아웃)는 다시 보내면 나을 수 있다
            if (isTimeout(e)) {
                throw new Yes24TransientException(Kind.TIMEOUT, "YES24 타임아웃", e);
            }
            throw new Yes24TransientException(Kind.FAILED, "YES24 호출 실패", e);
        }

        observeRemainingDay(raw.headers(), sentAt);

        // 200인데 본문을 읽는 중에 마감이 지나면 스트림이 닫혀 '파싱 실패'로 보인다 → 타임아웃으로 분류.
        // 200이 아닌 응답은 상태 코드·헤더만으로 판정하므로(예: HTML 429 + 일일 잔량 0) 덮어쓰지 않는다
        if (raw.status() == 200 && raw.parseFailed()
                && Duration.ofNanos(System.nanoTime() - startedNanos).compareTo(deadline) >= 0) {
            throw new Yes24TransientException(Kind.TIMEOUT, "YES24 타임아웃(본문 수신 중)");
        }
        // 마감 전에 본문을 읽다 연결이 끊겼다 — 파싱 실패가 아니라 전송 실패라 다시 보낼 만하고, 서킷도 실패로 세야 한다.
        // 파싱 실패로 두면 YES24 앞단이 응답 도중 끊는 장애에서 재시도도 안 하고 서킷이 '답했다(성공)'로 센다.
        // 429는 상태 코드·헤더로 한도를 판정하므로(일일 중단) 그대로 둔다.
        // 한계: Content-Length 없이 Connection: close로 보내다 끊기면 정상 끝(EOF)과 구분되지 않아 JSON 파싱 실패로 남는다
        if (raw.transportFailure() != null && raw.status() != 429) {
            throw new Yes24TransientException(Kind.FAILED, "YES24 응답 수신 중 연결 끊김", raw.transportFailure());
        }
        return raw;
    }

    private Optional<Yes24Book> classify(String isbn13, RawResponse<Yes24ItemDetailResponse> raw, Instant sentAt) {
        String errorCode = raw.body() != null ? raw.body().errorCode() : null;

        if (raw.status() == 200) {
            return Optional.of(validate(isbn13, raw));
        }
        if (raw.status() == 404 && NOT_FOUND_CODE.equals(errorCode)) {
            return Optional.empty();
        }
        throw rateLimitedOrFailed(raw.status(), raw.headers(), errorCode, sentAt);
    }

    // 200도 '결과 없음'도 아닌 응답. 429는 일일(중단 시작)·초당으로 나누고 나머지는 실패다
    private Yes24Exception rateLimitedOrFailed(int status, HttpHeaders headers, String errorCode, Instant sentAt) {
        if (status == 429) {
            // 본문이 JSON이 아니어도(게이트웨이 HTML 등) 일일 잔량 0이면 일일 한도로 본다
            if (DAILY_LIMIT_CODE.equals(errorCode) || isZero(headers.getFirst("x-ratelimit-remaining-day"))) {
                Instant until = resetDay(headers, sentAt);
                dailyStop.stopUntil(until);
                countRateLimited("remote", "day");
                log.warn("YES24 일일 한도 소진(RATE_002): {}까지 호출 중단", until);
                return new Yes24Exception(Kind.DAILY_LIMIT, true, "YES24 일일 한도 초과");
            }
            // RATE_001 또는 코드 없는 429는 초당 한도로 본다
            countRateLimited("remote", "second");
            return new Yes24Exception(Kind.RATE_LIMITED, true, "YES24 초당 한도 초과");
        }
        String message = "YES24 오류 응답: HTTP " + status + (errorCode != null ? " " + errorCode : "");
        // 5xx는 YES24 쪽 일시 장애일 수 있어 다시 보낼 만하다. 4xx는 요청 자체가 틀려 같은 답이 온다
        return status >= 500
                ? new Yes24TransientException(Kind.FAILED, message)
                : new Yes24Exception(Kind.FAILED, true, message);
    }

    // 200이어도 정상 결과를 확보하지 못했으면 실패다 (점검 페이지, success=false, 다른 책 등)
    private Yes24Book validate(String isbn13, RawResponse<Yes24ItemDetailResponse> raw) {
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

    private void observeRemainingDay(HttpHeaders headers, Instant sentAt) {
        String value = headers.getFirst("x-ratelimit-remaining-day");
        if (value == null) {
            return;
        }
        try {
            double remaining = Double.parseDouble(value.trim());
            // NaN을 long으로 바꾸면 0이 되어 하루 내내 막힌다. 유한한 0 이상 값만 받는다
            if (!Double.isFinite(remaining) || remaining < 0) {
                return;
            }
            remainingDay.set(remaining);
            budget.observeRemaining(ExternalProvider.YES24, (long) remaining, sentAt);
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
        rateLimitedCounter(by, kind).increment();
    }

    private Counter rateLimitedCounter(String by, String kind) {
        return meterRegistry.counter("yes24.rate.limited", "by", by, "kind", kind);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static IOException ioCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException io) {
                return io;
            }
        }
        return null;
    }

    private record RawResponse<T>(int status, HttpHeaders headers, T body, boolean parseFailed,
                                  IOException transportFailure) {
    }
}
