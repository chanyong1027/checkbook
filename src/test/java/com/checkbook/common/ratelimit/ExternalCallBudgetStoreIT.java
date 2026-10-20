package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ExternalCallBudgetStoreIT {

    // 다른 테스트 컨텍스트의 동기화(오늘 날짜·실제 키)와 섞이지 않게 먼 과거 날짜와 테스트 전용 키를 쓴다
    private static final LocalDate DAY = LocalDate.of(2000, 1, 1);

    @Autowired
    private ExternalCallBudgetStore store;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM external_call_budget WHERE usage_date <= ?", DAY);
    }

    @Test
    void 처음엔_넣고_다음부터는_더한다() {
        store.add("test:a", DAY, 3);
        assertThat(store.load(DAY)).containsEntry("test:a", 3);
        store.add("test:a", DAY, 4);
        assertThat(store.load(DAY)).containsEntry("test:a", 7);
    }

    @Test
    void 날짜와_키마다_따로_쌓인다() {
        store.add("test:a", DAY, 3);
        store.add("test:a", DAY.minusDays(1), 10);
        store.add("test:elibrary:7", DAY, 2);

        assertThat(store.load(DAY)).containsOnly(
                org.assertj.core.api.Assertions.entry("test:a", 3),
                org.assertj.core.api.Assertions.entry("test:elibrary:7", 2));
    }

    @Test
    void 두_칸이_동시에_처음_더해도_합이_맞다() throws Exception {
        // 블루·그린 두 칸이 같은 순간 하루 첫 동기화를 하는 경우 — 한쪽 INSERT가 키 충돌이면 UPDATE로 다시 더한다
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                store.add("test:kakao", DAY, 5);
                return 0;
            }));
        }
        start.countDown();
        for (Future<Integer> f : results) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(store.load(DAY).get("test:kakao")).isEqualTo(threads * 5);
    }

    @Test
    void 재시작해도_DB에서_오늘_사용량을_이어받고_두_칸의_합이_맞다() {
        // 실제 날짜를 쓰는 시나리오라 이 테스트만의 시계(먼 과거 날짜의 정오)로 예산을 만든다
        DailyCounterTest.MutableClock clock = new DailyCounterTest.MutableClock(
                DAY.atTime(12, 0).atZone(DailyCounter.KST).toInstant());
        RateLimitProperties properties = new RateLimitProperties(true, null, java.util.Map.of(
                ExternalProvider.DATANARU, new RateLimitProperties.Provider(100, 90)), 1000, 200, 2);
        ExternalCallBudget blue = new ExternalCallBudget(properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), clock);
        ExternalCallBudget green = new ExternalCallBudget(properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), clock);
        ExternalCallBudgetSyncer blueSyncer = new ExternalCallBudgetSyncer(blue, store, meterRegistry);
        ExternalCallBudgetSyncer greenSyncer = new ExternalCallBudgetSyncer(green, store, meterRegistry);

        for (int i = 0; i < 30; i++) {
            blue.acquire(ExternalProvider.DATANARU);
        }
        for (int i = 0; i < 20; i++) {
            green.acquire(ExternalProvider.DATANARU);
        }
        blueSyncer.sync();
        greenSyncer.sync();
        blueSyncer.sync();

        // 두 칸이 서로의 사용량을 본다
        assertThat(blue.used(ExternalProvider.DATANARU)).isEqualTo(50);
        assertThat(green.used(ExternalProvider.DATANARU)).isEqualTo(50);

        // 블루가 재시작했다 — 새 인스턴스가 기동 때 DB 합계를 읽는다
        ExternalCallBudget restarted = new ExternalCallBudget(properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), clock);
        new ExternalCallBudgetSyncer(restarted, store, meterRegistry).sync();
        assertThat(restarted.used(ExternalProvider.DATANARU)).isEqualTo(50);
    }
}
