package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 요청 제한 부품. 웹 등록은 {@link RateLimitWebConfig}가 맡는다(@WebMvcTest 슬라이스에서도 뜨게 나눔). */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public ExternalCallBudget externalCallBudget(RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new ExternalCallBudget(properties, meterRegistry, Clock.systemUTC());
    }

    @Bean
    public ELibraryConcurrencyLimiter eLibraryConcurrencyLimiter(RateLimitProperties properties) {
        return new ELibraryConcurrencyLimiter(properties);
    }

    @Bean
    public ClientDailyLimitInterceptor clientDailyLimitInterceptor(RateLimitProperties properties, MeterRegistry meterRegistry) {
        return new ClientDailyLimitInterceptor(properties, meterRegistry, Clock.systemUTC());
    }
}
