package com.checkbook.client.yes24;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
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
}
