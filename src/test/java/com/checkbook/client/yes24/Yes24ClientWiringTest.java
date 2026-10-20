package com.checkbook.client.yes24;

import com.checkbook.client.millie.MillieClientException;
import com.checkbook.client.yes24.Yes24Exception.Kind;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yes24Client는 Boot 자동 설정의 RestClient.Builder를 주입받는다. Boot 4에서 이 Bean은 spring-boot-restclient
 * 모듈에 있다. 이 테스트는 모듈이 아예 빠진 경우만 잡는다 — testImplementation으로 옮기면 테스트에서는
 * 여전히 Bean이 생겨 통과하고 운영 기동만 실패한다. 그래서 build.gradle에서 main 의존성으로 두고 주석으로 막는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class Yes24ClientWiringTest {

    @Autowired
    private Yes24Client yes24Client;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Test
    void clientIsWiredWithInjectedBuilder() {
        assertThat(yes24Client).isNotNull();
    }

    // 계약 6절: 100ms마다 1건, 대기 0
    @Test
    void selfRateLimiterMatchesContract() {
        RateLimiterConfig config = rateLimiterRegistry.rateLimiter(Yes24ClientConfig.RATE_LIMITER_NAME).getRateLimiterConfig();

        assertThat(config.getLimitRefreshPeriod()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.getLimitForPeriod()).isEqualTo(1);
        assertThat(config.getTimeoutDuration()).isEqualTo(Duration.ZERO);
    }

    // ---- 회복력 설정이 application.yaml에서 읽히는가 (T4 결정 카드 2·6, 규칙 2·3) ----

    @Test
    void identificationRetryRetriesOnlyTransientFailuresOnceWithJitter() {
        RetryConfig config = retryRegistry.retry("yes24").getRetryConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(2);
        assertThat(config.getExceptionPredicate().test(new Yes24TransientException(Kind.TIMEOUT, "t"))).isTrue();
        assertThat(config.getExceptionPredicate().test(new Yes24Exception(Kind.FAILED, true, "4xx"))).isFalse();
        assertThat(config.getExceptionPredicate().test(new Yes24Exception(Kind.RATE_LIMITED, false, "선제한"))).isFalse();
        // 150ms ± 33% → 100~200ms. 선제한 주기(100ms)보다 길어야 빠른 실패 뒤 재시도가 같은 주기 허가에 막히지 않는다
        for (int i = 0; i < 200; i++) {
            long waitMs = config.getIntervalBiFunction().apply(1, Either.left(new Yes24TransientException(Kind.TIMEOUT, "t")));
            assertThat(waitMs).isBetween(100L, 200L);
        }
    }

    @Test
    void yes24CircuitCountsOnlyTransientFailuresAndIgnoresCallsNotSent() {
        CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("yes24").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getRecordExceptionPredicate().test(new Yes24TransientException(Kind.FAILED, "503"))).isTrue();
        assertThat(config.getRecordExceptionPredicate().test(new Yes24Exception(Kind.RATE_LIMITED, true, "RATE_001"))).isFalse();
        assertThat(config.getIgnoreExceptionPredicate().test(new Yes24Exception(Kind.DAILY_LIMIT, false, "예산"))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(new Yes24TransientException(Kind.TIMEOUT, "t"))).isFalse();
    }

    @Test
    void millieCircuitCountsClientFailuresAndIgnoresBudget() {
        CircuitBreakerConfig config = circuitBreakerRegistry.circuitBreaker("millie").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getRecordExceptionPredicate().test(new MillieClientException("밀리 HTTP 403"))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate()
                .test(new DailyBudgetExceededException(ExternalProvider.MILLIE, Duration.ofHours(1)))).isTrue();
    }
}
