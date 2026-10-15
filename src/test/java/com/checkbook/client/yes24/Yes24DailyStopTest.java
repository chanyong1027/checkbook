package com.checkbook.client.yes24;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Yes24DailyStopTest {

    private static final Instant T0 = Instant.parse("2026-10-15T14:00:00Z");

    @Test
    void inactiveUntilStopped() {
        Yes24DailyStop stop = new Yes24DailyStop();

        assertThat(stop.isActive(T0)).isFalse();
    }

    @Test
    void activeBeforeUntilAndInactiveFromUntil() {
        Yes24DailyStop stop = new Yes24DailyStop();
        stop.stopUntil(T0.plusSeconds(60));

        assertThat(stop.isActive(T0.plusSeconds(59))).isTrue();
        assertThat(stop.isActive(T0.plusSeconds(60))).isFalse();
    }

    // 이미 나간 요청들의 RATE_002가 순서 없이 도착해도 가장 늦은 해제 시각을 유지한다
    @Test
    void keepsLatestUntilRegardlessOfArrivalOrder() {
        Yes24DailyStop stop = new Yes24DailyStop();
        stop.stopUntil(T0.plusSeconds(3600));
        stop.stopUntil(T0.plusSeconds(60));

        assertThat(stop.isActive(T0.plusSeconds(3599))).isTrue();
    }

    @Test
    void concurrentStopsKeepLatest() throws Exception {
        Yes24DailyStop stop = new Yes24DailyStop();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 1; i <= 100; i++) {
            Instant until = T0.plusSeconds(i);
            pool.submit(() -> {
                start.await();
                stop.stopUntil(until);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(stop.isActive(T0.plusSeconds(99))).isTrue();
        assertThat(stop.isActive(T0.plusSeconds(100))).isFalse();
    }
}
