package com.checkbook.client.yes24;

import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.client.yes24.dto.Yes24Book;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import com.checkbook.common.ratelimit.RateLimitProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class Yes24ClientTest {

    private static final String ISBN = "9788900000009";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private ExternalCallBudget budget = ExternalCallBudget.unlimited();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(kst(2026, 10, 15, 12, 0, 0));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ---- 지표 ----

    @Test
    void 한도_카운터를_기동_때_0으로_만든다() {
        newClient();

        // 처음 걸리는 순간에 생기면 첫 값이 1이라 increase()가 그 사건(RATE_002 등)을 못 센다
        var counters = meterRegistry.find("yes24.rate.limited").counters();
        assertThat(counters)
                .extracting(c -> c.getId().getTag("by") + "/" + c.getId().getTag("kind"))
                .containsExactlyInAnyOrder("self/day", "self/budget", "self/second", "remote/day", "remote/second");
        assertThat(counters).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    // ---- 찾음 / 없음 ----

    @Test
    void foundReturnsBookAndSendsKeyHeaderAndIsbnQuery() throws Exception {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));

        Optional<Yes24Book> result = newClient().lookupByIsbn(ISBN);

        assertThat(result).isPresent();
        Yes24Book book = result.get();
        assertThat(book.title()).isEqualTo("테스트 책");
        assertThat(book.author()).isEqualTo("홍길동 저/김번역 역");
        assertThat(book.publisher()).isEqualTo("테스트출판");
        assertThat(book.isbn13()).isEqualTo(ISBN);
        assertThat(book.salePrice()).isEqualTo(13500);
        assertThat(book.link()).isEqualTo("https://www.yes24.com/product/goods/1001");
        assertThat(book.coverUrl()).isEqualTo("https://image.yes24.com/goods/1001/XL");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/v1/goods/itemDetail");
        assertThat(request.getRequestUrl().queryParameter("searchType")).isEqualTo("ISBN13");
        assertThat(request.getRequestUrl().queryParameter("query")).isEqualTo(ISBN);
        assertThat(request.getRequestUrl().queryParameter("detail")).isEqualTo("N");
        assertThat(request.getHeader("X-Api-Key")).isEqualTo("test-key");
        // SimpleClientHttpRequestFactory 기본 Accept(HTML 편향)로 다른 응답을 받은 전례가 있어 명시를 고정
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.getHeader("User-Agent")).startsWith("CheckBook/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "null"})
    void foundWithoutPriceReturnsNullPrice(String price) {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", price)));

        Yes24Book book = newClient().lookupByIsbn(ISBN).orElseThrow();

        assertThat(book.salePrice()).isNull();
    }

    @Test
    void goods002IsNotFoundNotFailure() {
        server.enqueue(json(404, errorBody("GOODS_002")));

        assertThat(newClient().lookupByIsbn(ISBN)).isEmpty();
    }

    // 계약: '책 없음'은 GOODS_002만. 그 밖의 404는 정상 결과를 확보하지 못한 실패
    @Test
    void other404IsFailed() {
        server.enqueue(json(404, errorBody("GOODS_001")));

        assertFails(Kind.FAILED, true);
    }

    // ---- 200 응답 검증 ----

    @ParameterizedTest
    @ValueSource(strings = {
            // success=false
            "{\"success\":false,\"message\":\"x\",\"data\":null,\"errorCode\":\"SYS_001\"}",
            // data 없음
            "{\"success\":true,\"message\":\"성공\",\"data\":null,\"errorCode\":null}",
            // items 비어 있음
            "{\"success\":true,\"message\":\"성공\",\"data\":{\"items\":[]},\"errorCode\":null}",
            // 요청과 다른 ISBN
            "{\"success\":true,\"data\":{\"items\":[{\"title\":\"다른 책\",\"isbn13\":\"9791100000007\"}]}}",
            // 제목 없음
            "{\"success\":true,\"data\":{\"items\":[{\"title\":\" \",\"isbn13\":\"9788900000009\"}]}}",
            // JSON이 아님 (점검 페이지 등)
            "<html>점검 중</html>"
    })
    void invalid200ResponseIsFailed(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", body.startsWith("<") ? "text/html" : "application/json")
                .setBody(body));

        assertFails(Kind.FAILED, true);
    }

    @Test
    void serverErrorIsFailed() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertFails(Kind.FAILED, true);
    }

    @Test
    void slowResponseIsTimeoutWithoutRetry() {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)).setHeadersDelay(1, TimeUnit.SECONDS));

        assertFails(Kind.TIMEOUT, true);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // readTimeout은 본문 끝까지의 전체 마감이다. 헤더는 제때 왔는데 본문이 늦으면 스트림이 닫혀 파싱 실패로 보이지만 TIMEOUT이다
    @Test
    void slowBodyAfterHeadersIsTimeout() {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)).setBodyDelay(1, TimeUnit.SECONDS));

        assertFails(Kind.TIMEOUT, true);
    }

    // ---- 다시 보내면 나을 수 있는 실패(일시 오류) — 재시도·서킷이 이 타입만 센다(T4 결정 카드 T3) ----

    @Test
    void timeoutIsTransient() {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)).setHeadersDelay(1, TimeUnit.SECONDS));

        assertThat(lookupFailure(newClient())).isInstanceOf(Yes24TransientException.class);
    }

    @Test
    void slowBodyTimeoutIsTransient() {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)).setBodyDelay(1, TimeUnit.SECONDS));

        assertThat(lookupFailure(newClient())).isInstanceOf(Yes24TransientException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrorIsTransient(int status) {
        server.enqueue(new MockResponse().setResponseCode(status));

        Yes24Exception e = lookupFailure(newClient());

        assertThat(e).isInstanceOf(Yes24TransientException.class);
        assertThat(e.kind()).isEqualTo(Kind.FAILED);
    }

    @Test
    void connectionFailureIsTransient() throws Exception {
        Yes24Client client = newClient();
        server.shutdown();   // 이 주소로는 연결이 거절된다

        Yes24Exception e = lookupFailure(client);

        assertThat(e).isInstanceOf(Yes24TransientException.class);
        assertThat(e.sent()).isTrue();
    }

    // 다시 보내도 같은 답이 오는 실패는 일시 오류가 아니다(결정 카드 규칙 2)
    @Test
    void clientErrorAndLimitsAndInvalidResponsesAreNotTransient() {
        server.enqueue(json(404, errorBody("GOODS_001")));
        server.enqueue(json(400, errorBody("PARAM_001")));
        server.enqueue(json(429, errorBody("RATE_001")));
        server.enqueue(json(200, "{\"success\":false,\"message\":\"x\",\"data\":null,\"errorCode\":\"SYS_001\"}"));
        Yes24Client client = newClient();

        for (int i = 0; i < 4; i++) {
            assertThat(lookupFailure(client)).isNotInstanceOf(Yes24TransientException.class);
        }
    }

    // ---- YES24가 준 429 ----

    @Test
    void rate001IsRateLimitedByRemote() {
        server.enqueue(json(429, errorBody("RATE_001")));

        assertFails(Kind.RATE_LIMITED, true);
        assertThat(counter("remote", "second")).isEqualTo(1.0);
    }

    @Test
    void rate002StartsDailyStopUntilResetHeaderAndSkipsCallsMeanwhile() {
        Instant reset = kst(2026, 10, 16, 0, 0, 0);
        server.enqueue(json(429, errorBody("RATE_002"))
                .setHeader("x-ratelimit-reset-day", String.valueOf(reset.getEpochSecond())));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);
        assertThat(counter("remote", "day")).isEqualTo(1.0);

        // 중단 중에는 YES24를 부르지 않는다
        clock.set(reset.minusSeconds(1));
        assertFails(client, Kind.DAILY_LIMIT, false);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(counter("self", "day")).isEqualTo(1.0);

        // 해제 시각이 지나면 다시 부른다
        clock.set(reset);
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));
        assertThat(client.lookupByIsbn(ISBN)).isPresent();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // 헤더가 없으면 '요청을 보낸 시각' 기준 다음 KST 00시. 23:59 → 00:00 경계
    @Test
    void rate002WithoutResetHeaderStopsUntilNextKstMidnightFromRequestTime() {
        clock.set(kst(2026, 10, 15, 23, 59, 0));
        server.enqueue(json(429, errorBody("RATE_002")));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);

        clock.set(kst(2026, 10, 15, 23, 59, 59));
        assertFails(client, Kind.DAILY_LIMIT, false);

        clock.set(kst(2026, 10, 16, 0, 0, 0));
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));
        assertThat(client.lookupByIsbn(ISBN)).isPresent();
    }

    // 전날 보낸 요청의 RATE_002가 자정 뒤에 도착해도 새 날 전체를 막지 않는다
    @Test
    void lateRate002FromPreviousDayDoesNotStopNewDay() {
        clock.set(kst(2026, 10, 15, 23, 59, 59));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (server.getRequestCount() == 1) {
                    clock.set(kst(2026, 10, 16, 0, 0, 1));   // 응답이 자정 뒤에 도착
                    return json(429, errorBody("RATE_002"));
                }
                return json(200, foundBody(ISBN, "테스트 책", 13500.0));
            }
        });
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);

        assertThat(client.lookupByIsbn(ISBN)).isPresent();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    // 해제 시각 헤더가 비정상적으로 멀면(밀리초 단위 등) 믿지 않고 다음 KST 자정을 쓴다 — 재시작 전까지 영구 중단 방지
    @Test
    void absurdResetHeaderFallsBackToNextKstMidnight() {
        Instant sentAt = clock.instant();
        server.enqueue(json(429, errorBody("RATE_002"))
                .setHeader("x-ratelimit-reset-day", String.valueOf(sentAt.toEpochMilli())));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);

        clock.set(kst(2026, 10, 16, 0, 0, 0));
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));
        assertThat(client.lookupByIsbn(ISBN)).isPresent();
    }

    // 과거 해제 시각(시계 차이, '남은 초' 형식 등)이면 중단이 안 걸려 매 요청이 다시 RATE_002를 받는다 → 다음 KST 자정
    @Test
    void pastResetHeaderFallsBackToNextKstMidnight() {
        server.enqueue(json(429, errorBody("RATE_002")).setHeader("x-ratelimit-reset-day", "3600"));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);
        assertFails(client, Kind.DAILY_LIMIT, false);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // 게이트웨이가 HTML로 429를 줘도 일일 잔량 헤더가 0이면 일일 한도로 보고 중단한다
    @Test
    void nonJson429WithZeroRemainingDayStartsDailyStop() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "text/html")
                .setHeader("x-ratelimit-remaining-day", "0")
                .setBody("<html>Too Many Requests</html>"));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);
        assertFails(client, Kind.DAILY_LIMIT, false);
    }

    // 본문이 마감에 잘려도 429의 판정은 헤더로 한다 — 일일 중단이 시작돼야 한다
    @Test
    void slowBody429WithZeroRemainingDayStillStartsDailyStop() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "text/html")
                .setHeader("x-ratelimit-remaining-day", "0")
                .setBody("<html>Too Many Requests</html>")
                .setBodyDelay(1, TimeUnit.SECONDS));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);
        assertFails(client, Kind.DAILY_LIMIT, false);
        assertThat(meterRegistry.get("yes24.remaining.day").gauge().value()).isZero();
    }

    @Test
    void nonJson429WithoutDailySignalIsPerSecond() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>Too Many Requests</html>"));

        assertFails(Kind.RATE_LIMITED, true);
    }

    @Test
    void propertiesToStringMasksApiKey() {
        Yes24Properties properties = new Yes24Properties("https://apis.yes24.com", "secret-key",
                Duration.ofMillis(200), Duration.ofMillis(700), Duration.ofSeconds(3));

        assertThat(properties.toString()).doesNotContain("secret-key").contains("****");
    }

    // 재시작하면 중단 상태는 사라진다(인메모리). 다시 RATE_002를 받아야 중단한다
    @Test
    void freshClientHasNoDailyStop() {
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));

        assertThat(newClient().lookupByIsbn(ISBN)).isPresent();
    }

    // ---- 우리 쪽 선제한 ----

    @Test
    void selfRateLimitRejectsWithoutCalling() {
        RateLimiter strict = RateLimiter.of("yes24-test", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        Yes24Client client = newClient(strict);
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));

        assertThat(client.lookupByIsbn(ISBN)).isPresent();
        assertFails(client, Kind.RATE_LIMITED, false);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(counter("self", "second")).isEqualTo(1.0);
    }

    // ---- 잔량 게이지 ----

    @Test
    void remainingDayGaugeIsUnknownUntilFirstResponseThenTracksHeader() {
        Yes24Client client = newClient();
        assertThat(meterRegistry.get("yes24.remaining.day").gauge().value()).isNaN();

        server.enqueue(json(404, errorBody("GOODS_002")).setHeader("x-ratelimit-remaining-day", "19993"));
        client.lookupByIsbn(ISBN);

        assertThat(meterRegistry.get("yes24.remaining.day").gauge().value()).isEqualTo(19993.0);
    }

    // ---- 하루 예산 (요청 제한 결정 카드 1) ----

    @Test
    void 하루_예산이_차단선에_닿으면_보내지_않고_DAILY_LIMIT() {
        budget = yes24Budget(10, 1);
        Yes24Client client = newClient();
        server.enqueue(json(200, foundBody(ISBN, "테스트 책", 13500.0)));
        client.lookupByIsbn(ISBN);

        assertFails(client, Kind.DAILY_LIMIT, false);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(counter("self", "budget")).isEqualTo(1.0);
    }

    @Test
    void 응답_헤더의_남은_한도로_예산_사용량을_맞춘다() {
        budget = yes24Budget(20000, 19000);
        server.enqueue(json(404, errorBody("GOODS_002")).setHeader("x-ratelimit-remaining-day", "1000"));

        newClient().lookupByIsbn(ISBN);

        // 재시작 직후라 우리는 1회만 셌지만 YES24 기준 사용량은 19,000
        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(19000);
        assertThat(budget.isExhausted(ExternalProvider.YES24)).isTrue();
    }

    @Test
    void 자정_전에_보낸_요청의_잔량은_새_날_예산에_반영하지_않는다() {
        clock.set(kst(2026, 10, 15, 23, 59, 59));
        budget = yes24Budget(20000, 19000);
        // 응답이 자정을 넘겨 도착한다. 헤더 잔량 300은 전날 창의 값이다(리뷰 F1)
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                clock.set(kst(2026, 10, 16, 0, 0, 1));
                return json(404, errorBody("GOODS_002")).setHeader("x-ratelimit-remaining-day", "300");
            }
        });

        newClient().lookupByIsbn(ISBN);

        assertThat(budget.used(ExternalProvider.YES24)).isZero();
        assertThat(budget.isExhausted(ExternalProvider.YES24)).isFalse();
    }

    @Test
    void 예산이_이미_소진이면_초당_허가를_쓰지_않는다() {
        budget = yes24Budget(10, 0);
        RateLimiter limiter = RateLimiter.of("yes24-budget", RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofHours(1))
                .timeoutDuration(Duration.ZERO)
                .build());

        assertFails(newClient(limiter), Kind.DAILY_LIMIT, false);

        // 남은 허가는 배치(categoryList) 몫으로 남아 있어야 한다(리뷰 F5)
        assertThat(limiter.getMetrics().getAvailablePermissions()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-5", "abc"})
    void 이상한_잔량_헤더는_예산에_반영하지_않는다(String header) {
        budget = yes24Budget(20000, 19000);
        server.enqueue(json(404, errorBody("GOODS_002")).setHeader("x-ratelimit-remaining-day", header));

        newClient().lookupByIsbn(ISBN);

        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(1);
    }

    // ---- helpers ----

    private Yes24Client newClient() {
        return newClient(RateLimiter.of("yes24-test", RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build()));
    }

    private Yes24Client newClient(RateLimiter rateLimiter) {
        Yes24Properties properties = new Yes24Properties(
                server.url("/").toString().replaceAll("/$", ""),
                "test-key",
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ofSeconds(3));
        return new Yes24Client(RestClient.builder(), properties, rateLimiter, meterRegistry, clock, budget);
    }

    private ExternalCallBudget yes24Budget(int dailyLimit, int blockAt) {
        return new ExternalCallBudget(
                new RateLimitProperties(true, null,
                        Map.of(ExternalProvider.YES24, new RateLimitProperties.Provider(dailyLimit, blockAt)), 1000, 200, 2),
                new SimpleMeterRegistry(), clock);
    }

    private void assertFails(Kind kind, boolean sent) {
        assertFails(newClient(), kind, sent);
    }

    private void assertFails(Yes24Client client, Kind kind, boolean sent) {
        Yes24Exception e = catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));
        assertThat(e).as("Yes24Exception 기대").isNotNull();
        assertThat(e.kind()).isEqualTo(kind);
        assertThat(e.sent()).as("YES24로 요청을 보냈는가").isEqualTo(sent);
    }

    private static Yes24Exception lookupFailure(Yes24Client client) {
        Yes24Exception e = catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));
        assertThat(e).as("Yes24Exception 기대").isNotNull();
        return e;
    }

    private double counter(String by, String kind) {
        var c = meterRegistry.find("yes24.rate.limited").tag("by", by).tag("kind", kind).counter();
        return c == null ? 0.0 : c.count();
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String foundBody(String isbn, String title, Object salePrice) {
        return """
                {"success":true,"message":"성공","data":{"meta":{"query":"%s"},"items":[{
                "sortOrder":1,"itemId":1001,"title":"%s","author":"홍길동 저/김번역 역","publisher":"테스트출판",
                "isbn13":"%s","shopPrice":15000.0,"salePrice":%s,"itemStatus":"판매중",
                "cover":"https://image.yes24.com/goods/1001/XL","link":"https://www.yes24.com/product/goods/1001"}],
                "currentPage":1,"pageSize":1,"totalCount":1},"errorCode":null}
                """.formatted(isbn, title, isbn, salePrice);
    }

    private static String errorBody(String code) {
        return "{\"success\":false,\"message\":\"오류\",\"data\":null,\"errorCode\":\"" + code + "\"}";
    }

    private static Instant kst(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).atZone(KST).toInstant();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
