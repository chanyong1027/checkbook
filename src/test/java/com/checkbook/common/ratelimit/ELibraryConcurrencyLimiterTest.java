package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ELibraryConcurrencyLimiterTest {

    private static ELibraryConcurrencyLimiter limiter(boolean enabled, int perLibrary) {
        return new ELibraryConcurrencyLimiter(new RateLimitProperties(enabled, null, Map.of(), 1000, 200, perLibrary));
    }

    @Test
    void 사이트당_상한까지_얻고_넘으면_기다리다_못_얻는다() {
        ELibraryConcurrencyLimiter limiter = limiter(true, 2);

        assertThat(limiter.tryAcquire(1L, 0)).isTrue();
        assertThat(limiter.tryAcquire(1L, 0)).isTrue();
        long start = System.nanoTime();
        assertThat(limiter.tryAcquire(1L, 50)).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(40);
    }

    @Test
    void 다른_사이트는_따로_센다() {
        ELibraryConcurrencyLimiter limiter = limiter(true, 1);
        assertThat(limiter.tryAcquire(1L, 0)).isTrue();

        assertThat(limiter.tryAcquire(2L, 0)).isTrue();
    }

    @Test
    void 기다리는_동안_자리가_나면_얻는다() throws Exception {
        ELibraryConcurrencyLimiter limiter = limiter(true, 1);
        assertThat(limiter.tryAcquire(1L, 0)).isTrue();

        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> limiter.tryAcquire(1L, 5_000));
        Thread.sleep(50);
        limiter.release(1L);

        assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 꺼져_있으면_막지_않는다() {
        // 부하 측정은 WireMock 한 호스트로 몰려 상한에 걸리면 측정이 왜곡된다
        ELibraryConcurrencyLimiter limiter = limiter(false, 1);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(1L, 0)).isTrue();
        }
        limiter.release(1L);
    }

    @Test
    void 기다리다_인터럽트되면_못_얻고_인터럽트_표시는_남긴다() {
        ELibraryConcurrencyLimiter limiter = limiter(true, 1);
        assertThat(limiter.tryAcquire(1L, 0)).isTrue();

        Thread.currentThread().interrupt();
        try {
            assertThat(limiter.tryAcquire(1L, 1_000)).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
