package com.checkbook.client.yes24;

import com.checkbook.client.yes24.Yes24ClientTest.MutableClock;
import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.client.yes24.dto.Yes24CategoryListResponse.Item;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 메인 추천 배치용 목록 조회(종합 베스트셀러·주목할 신상품). 식별과 같은 선제한·일일 중단을 쓰고,
 * 배치만 허가를 최대 1초 기다리며, 전체 마감은 목록 전용 값(운영 3초)을 쓴다 — PR-3 결정 카드 3·4.
 */
class Yes24ClientCategoryListTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String ISBN = "9788900000009";

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private ExternalCallBudget budget = ExternalCallBudget.unlimited();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(LocalDateTime.of(2026, 10, 19, 4, 0).atZone(KST).toInstant());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // ---- 요청 형식과 정상 응답 ----

    @ParameterizedTest
    @CsvSource({
            "BESTSELLER, /v1/category/bestseller",
            "NEW_ATTENTION, /v1/category/newproductAttention"
    })
    void listSendsCategoryPageSizeAndReturnsItems(Yes24CategoryList list, String path) throws Exception {
        server.enqueue(json(200, listBody()));

        List<Item> items = newClient().categoryList(list, "001", 30);

        assertThat(items).hasSize(2);
        Item first = items.get(0);
        assertThat(first.title()).isEqualTo("첫 책");
        assertThat(first.author()).isEqualTo("홍길동 저");
        assertThat(first.publisher()).isEqualTo("테스트출판");
        assertThat(first.isbn13()).isEqualTo(ISBN);
        assertThat(first.cover()).isEqualTo("https://image.yes24.com/goods/1001/L");
        assertThat(first.publishDate()).isEqualTo("20261016");
        assertThat(first.adultYn()).isEqualTo("N");
        assertThat(items.get(1).isbn13()).isNull();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo(path);
        assertThat(request.getRequestUrl().queryParameter("categoryId")).isEqualTo("001");
        assertThat(request.getRequestUrl().queryParameter("pageSize")).isEqualTo("30");
        // 표본(10-15 실측)과 같은 요청. detail은 문서상 기본 N이라 보내지 않는다
        assertThat(request.getRequestUrl().queryParameterNames()).containsExactlyInAnyOrder("categoryId", "pageSize");
        assertThat(request.getHeader("X-Api-Key")).isEqualTo("test-key");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
    }

    // 결과 없음 코드(BEST_001, ATTN_001)는 빈 목록 — 섹션을 실패로 볼지는 호출자가 정한다
    @ParameterizedTest
    @CsvSource({"BESTSELLER, BEST_001", "NEW_ATTENTION, ATTN_001"})
    void noResultCodeIsEmptyList(Yes24CategoryList list, String code) {
        server.enqueue(json(404, errorBody(code)));

        assertThat(newClient().categoryList(list, "001", 30)).isEmpty();
    }

    @Test
    void other404IsFailed() {
        server.enqueue(json(404, errorBody("CATEGORY_001")));

        assertFails(newClient(), Kind.FAILED, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":false,\"message\":\"x\",\"data\":null,\"errorCode\":\"SYS_001\"}",
            "{\"success\":true,\"message\":\"성공\",\"data\":null,\"errorCode\":null}",
            "{\"success\":true,\"message\":\"성공\",\"data\":{\"currentPage\":1},\"errorCode\":null}",
            "<html>점검 중</html>"
    })
    void invalid200IsFailed(String body) {
        server.enqueue(json(200, body));

        assertFails(newClient(), Kind.FAILED, true);
    }

    @Test
    void serverErrorIsFailed() {
        server.enqueue(json(500, errorBody("SYS_001")));

        assertFails(newClient(), Kind.FAILED, true);
    }

    // ---- 타임아웃: 목록은 식별(여기서 300ms)과 다른 전체 마감(여기서 1초)을 쓴다 ----

    @Test
    void listUsesItsOwnLongerDeadline() {
        server.enqueue(json(200, listBody()).setBodyDelay(500, TimeUnit.MILLISECONDS));

        assertThat(newClient().categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).hasSize(2);
    }

    @Test
    void slowerThanListDeadlineIsTimeout() {
        server.enqueue(json(200, listBody()).setBodyDelay(2, TimeUnit.SECONDS));

        assertFails(newClient(), Kind.TIMEOUT, true);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ---- 선제한: 같은 제한기를 쓰되 배치는 최대 1초 기다린다 ----

    @Test
    void waitsForPermitHeldByIdentification() {
        RateLimiter limiter = limiter(1, Duration.ofMillis(300));
        Yes24Client client = newClient(limiter);
        server.enqueue(json(404, errorBody("GOODS_002")));
        server.enqueue(json(200, listBody()));

        assertThat(client.lookupByIsbn(ISBN)).isEmpty();   // 이번 주기 허가를 식별이 가져감
        assertThat(client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).hasSize(2);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(counter("self", "second")).isZero();
    }

    @Test
    void givesUpAfterMaxWaitWithoutCalling() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
        Yes24Client client = newClient(limiter);
        server.enqueue(json(404, errorBody("GOODS_002")));
        client.lookupByIsbn(ISBN);

        long started = System.nanoTime();
        assertFails(client, Kind.RATE_LIMITED, false);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(waitedMs).isBetween(900L, 2_000L);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(counter("self", "second")).isEqualTo(1.0);
    }

    // 식별 경로는 지금처럼 대기 0 — 배치용 대기가 사용자 요청으로 새지 않는다
    @Test
    void identificationStillRejectsImmediately() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
        Yes24Client client = newClient(limiter);
        server.enqueue(json(200, listBody()));
        client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30);

        long started = System.nanoTime();
        Yes24Exception e = catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(waitedMs).isLessThan(200L);
    }

    // ---- 한도: 일일 중단은 식별과 공유한다 ----

    @Test
    void dailyStopFromIdentificationSkipsListImmediately() {
        Yes24Client client = newClient();
        server.enqueue(json(429, errorBody("RATE_002")));
        catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));

        long started = System.nanoTime();
        assertFails(client, Kind.DAILY_LIMIT, false);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(200L);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // 제한기가 막혀 있어도 일일 중단이면 1초를 기다리지 않고 바로 실패한다
    @Test
    void dailyStopIsCheckedBeforeWaitingForPermit() {
        RateLimiter limiter = limiter(1, Duration.ofMinutes(1));
        Yes24Client client = newClient(limiter);
        server.enqueue(json(429, errorBody("RATE_002")));
        catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));   // 허가도 소진

        long started = System.nanoTime();
        assertFails(client, Kind.DAILY_LIMIT, false);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(200L);
        assertThat(counter("self", "day")).isEqualTo(1.0);
        assertThat(counter("self", "second")).isZero();
    }

    // 허가를 기다리는 동안 이미 나간 식별 요청이 RATE_002를 받으면, 허가를 얻은 뒤에도 보내지 않는다.
    // 순서를 시간이 아니라 래치로 고정한다:
    //   식별 요청 전송 → 목록 호출이 첫 확인을 통과하고 허가를 기다림 → 그제야 서버가 RATE_002 응답 → 중단 시작 → 허가 지급
    @Test
    void dailyStopStartedWhileWaitingPreventsSending() throws Exception {
        CountDownLatch listWaiting = new CountDownLatch(1);
        CountDownLatch lookupFinished = new CountDownLatch(1);
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.acquirePermission())
                .thenReturn(true)                                   // 식별 요청
                .thenAnswer(invocation -> {                         // 목록 호출: 첫 확인은 이미 통과한 상태
                    listWaiting.countDown();
                    return lookupFinished.await(5, TimeUnit.SECONDS);
                });
        // 식별 마감(기본 300ms)은 전송부터 재므로, 바쁜 CI에서 래치 순서가 끝나기 전에 넘지 않게 넉넉히
        Yes24Client client = newClient(limiter, Duration.ofSeconds(2));
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                listWaiting.await(5, TimeUnit.SECONDS);
                return json(429, errorBody("RATE_002"));
            }
        });

        AtomicReference<Yes24Exception> lookupFailure = new AtomicReference<>();
        Thread inFlight = new Thread(() -> {
            try {
                lookupFailure.set(catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN)));
            } finally {
                lookupFinished.countDown();
            }
        });
        inFlight.start();
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).as("식별 요청이 먼저 나감").isNotNull();

        assertFails(client, Kind.DAILY_LIMIT, false);

        inFlight.join(5_000);
        assertThat(inFlight.isAlive()).isFalse();
        assertThat(lookupFailure.get()).isNotNull();
        assertThat(lookupFailure.get().kind()).isEqualTo(Kind.DAILY_LIMIT);
        assertThat(lookupFailure.get().sent()).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(1);
        // 두 번째 확인까지 왔음(목록 호출이 허가를 받은 뒤 막혔음)을 보장
        verify(limiter, times(2)).acquirePermission();
    }

    @Test
    void rate002OnListStopsIdentificationToo() {
        Instant reset = LocalDateTime.of(2026, 10, 20, 0, 0).atZone(KST).toInstant();
        server.enqueue(json(429, errorBody("RATE_002"))
                .setHeader("x-ratelimit-reset-day", String.valueOf(reset.getEpochSecond())));
        Yes24Client client = newClient();

        assertFails(client, Kind.DAILY_LIMIT, true);

        Yes24Exception e = catchThrowableOfType(Yes24Exception.class, () -> client.lookupByIsbn(ISBN));
        assertThat(e.kind()).isEqualTo(Kind.DAILY_LIMIT);
        assertThat(e.sent()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void rate001OnListIsRemoteRateLimited() {
        server.enqueue(json(429, errorBody("RATE_001")));

        assertFails(newClient(), Kind.RATE_LIMITED, true);
        assertThat(counter("remote", "second")).isEqualTo(1.0);
    }

    @Test
    void listResponseUpdatesRemainingDayGauge() {
        Yes24Client client = newClient();
        server.enqueue(json(200, listBody()).setHeader("x-ratelimit-remaining-day", "19990"));

        client.categoryList(Yes24CategoryList.NEW_ATTENTION, "001", 30);

        assertThat(meterRegistry.get("yes24.remaining.day").gauge().value()).isEqualTo(19990.0);
    }

    // ---- 하루 예산 ----

    @Test
    void 배치는_예산_차단선을_넘어도_보내고_센다() {
        budget = yes24Budget(10, 1);
        budget.acquire(ExternalProvider.YES24);
        server.enqueue(json(200, listBody()));

        newClient().categoryList(Yes24CategoryList.NEW_ATTENTION, "001", 30);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(2);
    }

    // ---- helpers ----

    private Yes24Client newClient() {
        return newClient(limiter(100, Duration.ofSeconds(1)));
    }

    private Yes24Client newClient(RateLimiter rateLimiter) {
        return newClient(rateLimiter, Duration.ofMillis(300));
    }

    private Yes24Client newClient(RateLimiter rateLimiter, Duration lookupReadTimeout) {
        Yes24Properties properties = new Yes24Properties(
                server.url("/").toString().replaceAll("/$", ""),
                "test-key",
                Duration.ofMillis(200),
                lookupReadTimeout,
                Duration.ofSeconds(1));
        return new Yes24Client(RestClient.builder(), properties, rateLimiter, meterRegistry, clock, budget);
    }

    private static ExternalCallBudget yes24Budget(int dailyLimit, int blockAt) {
        return new ExternalCallBudget(
                new RateLimitProperties(true, null,
                        Map.of(ExternalProvider.YES24, new RateLimitProperties.Provider(dailyLimit, blockAt)), 1000),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    private static RateLimiter limiter(int permits, Duration period) {
        return RateLimiter.of("yes24-test", RateLimiterConfig.custom()
                .limitForPeriod(permits)
                .limitRefreshPeriod(period)
                .timeoutDuration(Duration.ZERO)
                .build());
    }

    private void assertFails(Yes24Client client, Kind kind, boolean sent) {
        Yes24Exception e = catchThrowableOfType(Yes24Exception.class,
                () -> client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30));
        assertThat(e).as("Yes24Exception 기대").isNotNull();
        assertThat(e.kind()).isEqualTo(kind);
        assertThat(e.sent()).as("YES24로 요청을 보냈는가").isEqualTo(sent);
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

    // 구조만 흉내 낸 합성 응답(실제 응답 원문 아님). 두 번째 항목은 ISBN 없는 굿즈
    private static String listBody() {
        return """
                {"success":true,"message":"성공","errorCode":null,"data":{
                "meta":{"apiTitle":"합성","query":"categoryId=001"},
                "items":[
                 {"sortOrder":1,"itemId":1001,"title":"첫 책","author":"홍길동 저","goodsType":"도서",
                  "goodsSortNm":"국내도서-소설/시/희곡","adultYn":"N","publisher":"테스트출판",
                  "isbn13":"%s","salePrice":13500.0,"publishDate":"20261016","itemStatus":"판매중",
                  "cover":"https://image.yes24.com/goods/1001/L","link":"https://www.yes24.com/product/goods/1001",
                  "contentDetail":{"bookIntroduction":"소개"}},
                 {"sortOrder":2,"itemId":1002,"title":"굿즈 세트","author":null,"goodsType":"도서",
                  "adultYn":"N","publisher":"테스트출판","isbn13":null,"publishDate":"20261020"}
                ],
                "currentPage":1,"pageSize":30,"totalCount":2}}
                """.formatted(ISBN);
    }

    private static String errorBody(String code) {
        return "{\"success\":false,\"message\":\"오류\",\"data\":null,\"errorCode\":\"" + code + "\"}";
    }
}
