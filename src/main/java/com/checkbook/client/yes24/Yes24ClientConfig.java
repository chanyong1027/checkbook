package com.checkbook.client.yes24;

import com.checkbook.common.ratelimit.ExternalCallBudget;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(Yes24Properties.class)
public class Yes24ClientConfig {

    // 선제한 설정은 application.yaml resilience4j.ratelimiter.instances.yes24
    static final String RATE_LIMITER_NAME = "yes24";

    @Bean
    public Yes24Client yes24Client(
            RestClient.Builder restClientBuilder,   // Boot 자동 설정 빌더(prototype) — 관측(http.client.requests) 포함
            Yes24Properties properties,
            RateLimiterRegistry rateLimiterRegistry,
            MeterRegistry meterRegistry,
            ExternalCallBudget budget
    ) {
        return new Yes24Client(
                restClientBuilder,
                properties,
                rateLimiterRegistry.rateLimiter(RATE_LIMITER_NAME),
                meterRegistry,
                Clock.systemUTC(),
                budget);
    }
}
