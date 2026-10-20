package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalCallBudgetSyncerTest {

    // 2026-10-18 12:00 KST
    private final DailyCounterTest.MutableClock clock =
            new DailyCounterTest.MutableClock(Instant.parse("2026-10-18T03:00:00Z"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    private final ExternalCallBudgetStore store = mock(ExternalCallBudgetStore.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ExternalCallBudget budget(boolean enabled) {
        return new ExternalCallBudget(new RateLimitProperties(enabled, null, Map.of(
                ExternalProvider.DATANARU, new RateLimitProperties.Provider(100, 90)), 1000, 200, 2),
                new SimpleMeterRegistry(), clock);
    }

    @Test
    void 증가분을_DB에_더하고_DB_합계로_로컬_카운터를_올린다() {
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU);
        budget.acquire(ExternalProvider.DATANARU);
        // 다른 칸이 이미 50을 썼다
        when(store.load(TODAY)).thenReturn(Map.of("DATANARU", 52));

        new ExternalCallBudgetSyncer(budget, store, meterRegistry).sync();

        assertThat(budget.used(ExternalProvider.DATANARU)).isEqualTo(52);
    }

    @Test
    void 손대지_않은_키도_다른_칸의_사용량을_읽어_온다() {
        ExternalCallBudget budget = budget(true);
        when(store.load(TODAY)).thenReturn(Map.of("DATANARU", 95));

        new ExternalCallBudgetSyncer(budget, store, meterRegistry).sync();

        assertThat(budget.isExhausted(ExternalProvider.DATANARU)).isTrue();
        verify(store, never()).add(eq("DATANARU"), eq(TODAY), anyInt());
    }

    @Test
    void DB_실패면_증가분을_보관했다가_다음에_함께_더한다() {
        ExternalCallBudget budget = budget(true);
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry);
        budget.acquire(ExternalProvider.DATANARU);
        doThrow(new DataAccessResourceFailureException("DB 끊김")).when(store).add("DATANARU", TODAY, 1);

        syncer.sync();

        // 요청 처리는 그대로(로컬 카운터 유지)이고 실패를 센다
        assertThat(budget.used(ExternalProvider.DATANARU)).isEqualTo(1);
        assertThat(meterRegistry.counter("external.budget.sync.failures").count()).isEqualTo(1);

        budget.acquire(ExternalProvider.DATANARU);
        when(store.load(TODAY)).thenReturn(Map.of("DATANARU", 2));
        syncer.sync();

        verify(store).add("DATANARU", TODAY, 2);
    }

    @Test
    void 자정을_넘긴_증가분은_전날_행에_더하고_새_날_카운터는_올리지_않는다() {
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU); // 10-18
        clock.advance(Duration.ofHours(13));         // 10-19 01:00 KST
        when(store.load(TODAY.plusDays(1))).thenReturn(Map.of());

        new ExternalCallBudgetSyncer(budget, store, meterRegistry).sync();

        verify(store).add("DATANARU", TODAY, 1);
        assertThat(budget.used(ExternalProvider.DATANARU)).isZero();
    }

    @Test
    void 전자도서관_사이트별_키도_동기화한다() {
        ExternalCallBudget budget = budget(true);
        budget.acquireELibrary(7L, false);
        when(store.load(TODAY)).thenReturn(Map.of("elibrary:7", 1));

        new ExternalCallBudgetSyncer(budget, store, meterRegistry).sync();

        verify(store).add("elibrary:7", TODAY, 1);
    }

    @Test
    void 첫_쓰기가_실패하면_나머지는_시도하지_않고_되돌려_둔다() {
        // DB가 죽어 있으면 키마다 연결 대기(기본 30초)를 반복하지 않는다(리뷰 F1)
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU);
        budget.acquireELibrary(1L, false);
        budget.acquireELibrary(2L, false);
        doThrow(new DataAccessResourceFailureException("DB 끊김")).when(store).add(anyString(), any(), anyInt());
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry);

        syncer.sync();

        verify(store, times(1)).add(anyString(), any(), anyInt());
        verify(store, never()).load(any());
        // 셋 다 남아 있다가 복구 뒤 더해진다
        reset(store);
        when(store.load(TODAY)).thenReturn(Map.of());
        syncer.sync();
        verify(store, times(3)).add(anyString(), eq(TODAY), eq(1));
    }

    @Test
    void 쓰기는_됐는데_읽기가_실패하면_증가분을_되돌리지_않는다() {
        // 되돌리면 다음 동기화에서 같은 증가분을 두 번 더한다(리뷰 F2)
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU);
        when(store.load(TODAY)).thenThrow(new DataAccessResourceFailureException("읽기 실패"));
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry);

        syncer.sync();
        reset(store);
        when(store.load(TODAY)).thenReturn(Map.of());
        syncer.sync();

        verify(store, never()).add(anyString(), any(), anyInt());
    }

    @Test
    void 직전_동기화가_실패한_상태면_종료_때_다시_쓰지_않는다() {
        // DB가 죽어 있는데 종료 때 또 연결을 기다리면 docker stop 시간을 넘긴다(리뷰 F1)
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU);
        doThrow(new DataAccessResourceFailureException("DB 끊김")).when(store).add(anyString(), any(), anyInt());
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry);
        syncer.sync();
        reset(store);

        syncer.flushOnShutdown();

        verifyNoInteractions(store);
    }

    @Test
    void 읽기만_실패했으면_종료_때는_쓴다() {
        // 쓰기는 되는 상태라 종료 flush를 건너뛰면 마지막 10초치를 잃는다(2차 리뷰 N1)
        ExternalCallBudget budget = budget(true);
        when(store.load(TODAY)).thenThrow(new DataAccessResourceFailureException("읽기 실패"));
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry);
        syncer.sync();
        budget.acquire(ExternalProvider.DATANARU);

        syncer.flushOnShutdown();

        verify(store).add("DATANARU", TODAY, 1);
    }

    @Test
    void 기동하면_첫_동기화부터_자체_스레드에서_주기적으로_돈다() throws InterruptedException {
        // 기동 이벤트 스레드에서 돌면 DB 장애 때 기동을 붙잡는다. 공용 스케줄러 스레드면 추천 배치에 밀린다
        ExternalCallBudget budget = budget(true);
        CountDownLatch twoRuns = new CountDownLatch(2);
        List<String> threads = new CopyOnWriteArrayList<>();
        when(store.load(TODAY)).thenAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            twoRuns.countDown();
            return Map.of();
        });
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry, 20);

        syncer.start();
        try {
            assertThat(twoRuns.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threads).allMatch("budget-sync"::equals);
        } finally {
            syncer.flushOnShutdown();
        }
    }

    @Test
    void 종료하면_주기_실행을_멈추고_남은_증가분은_한_번만_쓴다() throws InterruptedException {
        ExternalCallBudget budget = budget(true);
        CountDownLatch firstRun = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        when(store.load(TODAY)).thenAnswer(invocation -> {
            runs.incrementAndGet();
            firstRun.countDown();
            return Map.of();
        });
        ExternalCallBudgetSyncer syncer = new ExternalCallBudgetSyncer(budget, store, meterRegistry, 20);
        syncer.start();
        assertThat(firstRun.await(5, TimeUnit.SECONDS)).isTrue();
        budget.acquire(ExternalProvider.DATANARU);

        syncer.flushOnShutdown();

        // 마지막 주기가 썼든 종료 flush가 썼든 한 번만 더해진다
        verify(store).add("DATANARU", TODAY, 1);
        int afterShutdown = runs.get();
        Thread.sleep(200); // 주기(20ms)의 10배 — 예약이 남아 있으면 그새 여러 번 돈다
        assertThat(runs.get()).isEqualTo(afterShutdown);
    }

    @Test
    void 제한이_꺼져_있으면_증가분도_쌓지_않는다() {
        ExternalCallBudget budget = budget(false);
        budget.acquire(ExternalProvider.DATANARU);

        assertThat(budget.drainPending()).isEmpty();
    }

    @Test
    void 제한이_꺼져_있으면_동기화하지_않는다() {
        ExternalCallBudget budget = budget(false);
        budget.acquire(ExternalProvider.DATANARU);

        new ExternalCallBudgetSyncer(budget, store, meterRegistry).sync();

        verifyNoInteractions(store);
    }
}
