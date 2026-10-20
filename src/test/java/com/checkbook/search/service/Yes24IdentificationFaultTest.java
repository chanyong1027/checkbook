package com.checkbook.search.service;

import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Properties;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YES24 고장을 가짜 서버로 일부러 내고, 재시도·서킷이 없을 때(전)와 있을 때(후)를 같은 조건으로 비교한다(T4 결정 카드 14).
 * 운영 트래픽이 적어 실측이 쌓이지 않으니, 바뀐 동작을 수치로 남기는 용도다. 수치는 표준 출력의 [T4 전후] 줄 —
 * 실제 네트워크 실패율은 배포 뒤 실제 클라이언트 탐침으로 따로 잰다. 시간은 운영 값(700ms)을 줄여(300ms) 시험을 빠르게 한다 —
 * 가짜 서버의 평소 응답이 약 50ms(가끔 200ms대)라 그보다 넉넉히 둔다. 150ms일 때 정상 요청이 가끔 시간 초과됐다.
 */
class Yes24IdentificationFaultTest {

    private static final String ISBN = "9788900000009";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    // 재시도 없음·서킷이 사실상 열리지 않는 설정 = 이번 PR 전의 동작
    private static final RetryConfig NO_RETRY = RetryConfig.custom().maxAttempts(1).build();
    private static final CircuitBreakerConfig NO_CIRCUIT = CircuitBreakerConfig.custom()
            .slidingWindowSize(1000).minimumNumberOfCalls(1000).build();

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // 100건 중 2건(첫 번째는 503, 51번째는 타임아웃)이 한 번 튄다 — 탐침에서 본 '한 번씩 튀고 앞뒤는 정상' 모양
    @Test
    void occasionalFailuresAreRecoveredByOneRetry() {
        IntFunction<MockResponse> blips = n -> n % 100 == 1 ? new MockResponse().setResponseCode(503)
                : n % 100 == 51 ? found().setHeadersDelay(600, TimeUnit.MILLISECONDS)
                : found();

        Outcome before = run(blips, 100, NO_RETRY, NO_CIRCUIT);
        Outcome after = run(blips, 100, BookIdentificationServiceTest.RETRY, BookIdentificationServiceTest.CIRCUIT);

        print("가끔 튐(2%)", before, after);
        // 로컬 가짜 서버도 드물게 느려 ±1건 흔들린다 — 그 흔들림도 재시도가 구한다
        assertThat(before.failed()).isGreaterThanOrEqualTo(2);
        assertThat(after.failed()).isZero();
        // 재시도만큼 YES24 호출(하루 예산)이 는다. 보통 103: 재시도로 요청 번호가 밀려 101번째(세 번째 튐)까지 가기 때문
        assertThat(after.requests()).isBetween(102, 104);
    }

    // YES24가 통째로 멈춘다 — 서킷이 열린 뒤에는 기다리지 않고, 부르지도 않는다
    @Test
    void outageStopsWaitingOnceCircuitOpens() {
        IntFunction<MockResponse> outage = n -> found().setHeadersDelay(600, TimeUnit.MILLISECONDS);

        Outcome before = run(outage, 12, NO_RETRY, NO_CIRCUIT);
        Outcome after = run(outage, 12, BookIdentificationServiceTest.RETRY, BookIdentificationServiceTest.CIRCUIT);

        print("전면 멈춤", before, after);
        assertThat(before.requests()).isEqualTo(12);
        assertThat(before.failed()).isEqualTo(12);
        // 5건 × 2번 시도 = 창 10건이 모두 실패 → 열림. 남은 7건은 YES24를 부르지도, 기다리지도 않는다
        assertThat(after.requests()).isEqualTo(10);
        assertThat(after.failed()).isEqualTo(12);
        assertThat(after.elapsedMs().subList(5, 12)).allSatisfy(ms -> assertThat(ms).isLessThan(50L));
    }

    private Outcome run(IntFunction<MockResponse> fault, int searches, RetryConfig retry, CircuitBreakerConfig circuit) {
        AtomicInteger requests = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return fault.apply(requests.incrementAndGet());
            }
        });
        BookIdentificationService service = new BookIdentificationService(newClient(), new SimpleMeterRegistry(),
                CircuitBreakerRegistry.of(circuit), RetryRegistry.of(retry));

        int failed = 0;
        List<Long> elapsedMs = new ArrayList<>();
        for (int i = 0; i < searches; i++) {
            long start = System.nanoTime();
            IdentificationResult result = service.identify(ISBN, null);
            elapsedMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            if (result.yes24() != Yes24Status.FOUND) {
                failed++;
            }
        }
        return new Outcome(searches, failed, requests.get(), elapsedMs);
    }

    private Yes24Client newClient() {
        Yes24Properties properties = new Yes24Properties(server.url("/").toString().replaceAll("/$", ""), "test-key",
                Duration.ofMillis(400), READ_TIMEOUT, Duration.ofSeconds(3));
        // 선제한은 이 시험의 관심사가 아니다 — 넉넉히
        RateLimiter rateLimiter = RateLimiter.of("yes24-fault", RateLimiterConfig.custom()
                .limitForPeriod(1000).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO).build());
        return new Yes24Client(RestClient.builder(), properties, rateLimiter, new SimpleMeterRegistry(),
                Clock.systemUTC(), ExternalCallBudget.unlimited());
    }

    private static MockResponse found() {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("""
                {"success":true,"message":"성공","data":{"items":[{"title":"테스트 책","author":"홍길동",
                "publisher":"테스트출판","isbn13":"9788900000009","salePrice":13500.0,
                "link":"https://www.yes24.com/product/goods/1001","cover":"https://image.yes24.com/goods/1001/XL"}]},
                "errorCode":null}""");
    }

    private static void print(String scenario, Outcome before, Outcome after) {
        System.out.printf("[T4 전후] %s | 전: 실패 %d/%d, YES24 호출 %d, 검색당 p50 %dms·최대 %dms, 합계 %dms"
                        + " | 후: 실패 %d/%d, YES24 호출 %d, 검색당 p50 %dms·최대 %dms, 합계 %dms%n",
                scenario, before.failed(), before.searches(), before.requests(), before.p50(), before.max(), before.total(),
                after.failed(), after.searches(), after.requests(), after.p50(), after.max(), after.total());
    }

    private record Outcome(int searches, int failed, int requests, List<Long> elapsedMs) {

        long p50() {
            return elapsedMs.stream().sorted().toList().get(elapsedMs.size() / 2);
        }

        long max() {
            return elapsedMs.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        long total() {
            return elapsedMs.stream().mapToLong(Long::longValue).sum();
        }
    }
}
