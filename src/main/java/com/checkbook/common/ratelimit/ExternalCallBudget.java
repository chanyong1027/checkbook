package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * 공급자별 하루 외부 호출 예산. 나가는 호출을 세고, 차단선에 닿으면 사용자 요청의 호출만 막는다.
 * 배치처럼 막으면 안 되는 호출은 {@link #record}로 세기만 한다.
 * 누가 쓰든 공급자 한도를 넘지 않게 하는 장치이고, 한 사람이 다 쓰는 것은 IP별 한도가 막는다.
 */
@Slf4j
public class ExternalCallBudget {

    private static final double WARN_RATIO = 0.8;
    private static final String WARN_KEY_PREFIX = "warn:";
    private static final String ELIBRARY_KEY_PREFIX = "elibrary:";
    private static final String EXHAUSTED_KEY_PREFIX = "exhausted:";

    private final boolean enabled;
    private final Map<ExternalProvider, RateLimitProperties.Provider> providers;
    private final int elibraryPerLibraryLimit;
    private final DailyCounter counter;
    private final MeterRegistry meterRegistry;

    public ExternalCallBudget(RateLimitProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.enabled = properties.enabled();
        this.providers = properties.providers();
        this.elibraryPerLibraryLimit = properties.elibraryPerLibraryLimit();
        // 키는 공급자 5개 + 경고 표시 + 전자도서관 사이트 수(수천 미만)라 상한은 넉넉히 둔다
        this.counter = new DailyCounter(clock, 100_000);
        this.meterRegistry = meterRegistry;

        for (ExternalProvider provider : ExternalProvider.values()) {
            if (provider == ExternalProvider.ELIBRARY) {
                continue;
            }
            Gauge.builder("external.budget.used", this, budget -> budget.used(provider))
                    .description("공급자별 오늘(KST) 나간 외부 호출 수")
                    .tag("provider", provider.tag())
                    .register(meterRegistry);
        }
    }

    /** 테스트·도구용: 막지 않는 예산 */
    public static ExternalCallBudget unlimited() {
        return new ExternalCallBudget(
                new RateLimitProperties(false, null, Map.of(), Integer.MAX_VALUE),
                new SimpleMeterRegistry(),
                Clock.systemUTC());
    }

    /** 호출 직전에 부른다. 차단선에 닿았으면 세지 않고 {@link DailyBudgetExceededException}. */
    public void acquire(ExternalProvider provider) {
        RateLimitProperties.Provider limits = providers.get(provider);
        if (!enabled || limits == null) {
            counter.increment(provider.name());
            return;
        }
        if (!counter.tryAcquire(provider.name(), limits.blockAt())) {
            reject(provider);
        }
        warnIfNearLimit(provider, limits);
    }

    /** 막지 않고 센다(배치 등 예산 여유분을 쓰는 호출). */
    public void record(ExternalProvider provider) {
        counter.increment(provider.name());
        RateLimitProperties.Provider limits = providers.get(provider);
        if (limits != null) {
            warnIfNearLimit(provider, limits);
        }
    }

    /**
     * 공급자가 응답 헤더로 알려 준 남은 한도. 재시작으로 0이 된 우리 카운터를 실제 사용량까지 올린다.
     *
     * @param sentAt 요청을 보낸 시각. 자정 전에 보낸 요청의 응답이 자정 뒤에 오면 그 잔량은 전날 창의 값이라
     *               새 날 카운터에 넣지 않는다(넣으면 하루 내내 막힌다. raiseTo는 올리기만 한다)
     */
    public void observeRemaining(ExternalProvider provider, long remaining, Instant sentAt) {
        RateLimitProperties.Provider limits = providers.get(provider);
        if (limits == null || remaining < 0) {
            return;
        }
        long used = limits.dailyLimit() - remaining;
        if (used > 0) {
            counter.raiseToIfDate(provider.name(), (int) Math.min(used, Integer.MAX_VALUE),
                    sentAt.atZone(DailyCounter.KST).toLocalDate());
        }
    }

    /** 전자도서관 사이트 하나에 대한 수집 직전에 부른다. */
    public void acquireELibrary(long libraryId) {
        String key = ELIBRARY_KEY_PREFIX + libraryId;
        if (!enabled) {
            counter.increment(key);
            return;
        }
        if (!counter.tryAcquire(key, elibraryPerLibraryLimit)) {
            reject(ExternalProvider.ELIBRARY, key);
        }
    }

    /** 차단선에 닿았으면 {@link DailyBudgetExceededException}. 호출이 아니라 결과 해석용이라 세지 않는다. */
    public void throwIfExhausted(ExternalProvider provider) {
        if (isExhausted(provider)) {
            throw new DailyBudgetExceededException(provider, counter.untilReset());
        }
    }

    public boolean isExhausted(ExternalProvider provider) {
        RateLimitProperties.Provider limits = providers.get(provider);
        return enabled && limits != null && used(provider) >= limits.blockAt();
    }

    public int used(ExternalProvider provider) {
        return counter.used(provider.name());
    }

    private void reject(ExternalProvider provider) {
        reject(provider, provider.name());
    }

    private void reject(ExternalProvider provider, String key) {
        meterRegistry.counter("external.budget.rejected", "provider", provider.tag()).increment();
        if (counter.markOnce(EXHAUSTED_KEY_PREFIX + key)) {
            log.warn("{} 하루 예산 차단선 도달 — 자정(KST)까지 사용자 요청 호출을 막음 (key={}, 사용 {})",
                    provider.tag(), key, counter.used(key));
        }
        throw new DailyBudgetExceededException(provider, counter.untilReset());
    }

    // 하루 한 번만 남긴다. 키가 '경고 표시' 역할을 한다
    private void warnIfNearLimit(ExternalProvider provider, RateLimitProperties.Provider limits) {
        int used = used(provider);
        if (used >= limits.dailyLimit() * WARN_RATIO
                && counter.markOnce(WARN_KEY_PREFIX + provider.name())) {
            log.warn("{} 오늘 호출 {}회 — 하루 한도 {}의 80% 도달 (차단선 {})",
                    provider.tag(), used, limits.dailyLimit(), limits.blockAt());
        }
    }
}
