package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 공급자 하루 예산을 DB와 맞춘다(리뷰 F2: 재시작·블루그린 전환에 카운터가 0이 되던 문제).
 * 기동 직후 오늘 합계를 읽고, 10초마다 증가분을 더한 뒤 합계를 다시 읽고, 종료 때 한 번 더 쓴다.
 * 주기 실행은 @Scheduled(공용 스케줄러 스레드 1개)가 아니라 자체 스레드로 한다 — 월·목 04시 추천 배치가 그 스레드를
 * 쥔 동안(최악 약 2분) 동기화가 멈추지 않게. 실행기는 빈으로 등록하지 않는다: TaskScheduler·ScheduledExecutorService
 * 빈이 생기면 Boot 기본 스케줄러가 물러나 추천 배치·스냅샷 정리까지 그 스레드로 몰린다.
 * DB가 응답하지 않으면 증가분을 보관해 다음에 함께 더한다(결정 카드 B1) — 그동안 요청 처리는 메모리 카운터로 계속된다.
 */
@Slf4j
@Component
public class ExternalCallBudgetSyncer {

    private final ExternalCallBudget budget;
    private final ExternalCallBudgetStore store;
    private final Counter failureCounter;
    private final long intervalMs;
    // 기동 스레드(ApplicationReady)가 쓰고 종료 스레드(@PreDestroy)가 읽는다
    private volatile ScheduledExecutorService executor;
    private boolean failing;
    // 종료 flush를 건너뛸지는 쓰기 실패로만 판단한다(읽기만 실패했으면 쓰기는 되는 상태)
    private boolean writeFailing;

    public ExternalCallBudgetSyncer(ExternalCallBudget budget, ExternalCallBudgetStore store, MeterRegistry meterRegistry) {
        this(budget, store, meterRegistry, 10_000);
    }

    @Autowired
    public ExternalCallBudgetSyncer(ExternalCallBudget budget, ExternalCallBudgetStore store, MeterRegistry meterRegistry,
                                    @Value("${rate-limit.sync-interval-ms:10000}") long intervalMs) {
        this.intervalMs = intervalMs;
        this.budget = budget;
        this.store = store;
        this.failureCounter = Counter.builder("external.budget.sync.failures")
                .description("공급자 하루 예산 DB 동기화 실패 횟수(실패해도 메모리로 계속 센다)")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        sync();
        if (!budget.isEnabled()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "budget-sync");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::syncSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    // ScheduledExecutorService는 작업이 예외를 던지면 그 뒤 실행을 조용히 멈춘다 — 무엇이 나든 다음 주기는 돌게 한다
    private void syncSafely() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("공급자 예산 동기화 중 예상 못 한 오류", e);
        }
    }

    // 직전 동기화의 쓰기가 실패한 상태(DB가 죽어 있음)면 종료 때 다시 연결을 기다리지 않는다 — 기다리면 docker stop
    // 시간을 넘긴다. 이때 남은 증가분은 잃는다(결정 문서의 알려진 한계)
    @PreDestroy
    public void flushOnShutdown() {
        if (executor != null) {
            executor.shutdown(); // 새 주기는 막고, 돌고 있는 한 번은 아래 synchronized flush가 기다린다
        }
        flush();
    }

    private synchronized void flush() {
        if (writeFailing) {
            log.warn("공급자 예산 DB 동기화가 실패 중이라 종료 때 쓰지 않음 — 이번 칸의 미반영 증가분은 잃는다");
            return;
        }
        sync();
    }

    // 스케줄·기동·종료가 겹쳐도 한 번에 하나만 돈다(증가분을 두 번 더하지 않게)
    synchronized void sync() {
        if (!budget.isEnabled()) {
            return; // 부하 측정 등 제한이 꺼진 프로필은 막지도 않으니 저장할 이유가 없다
        }
        List<ExternalCallBudget.PendingUsage> pending = budget.drainPending();
        for (int i = 0; i < pending.size(); i++) {
            try {
                store.add(pending.get(i).key(), pending.get(i).date(), pending.get(i).delta());
            } catch (DataAccessException e) {
                // 첫 실패에서 멈춘다. DB가 죽어 있으면 키마다 연결 대기(기본 30초)를 반복해 한 번이 수십 분이 될 수 있다
                pending.subList(i, pending.size()).forEach(budget::restorePending);
                writeFailing = true;
                report(e, "쓰기 — 메모리로 계속 세고 증가분은 다음 동기화에 더함");
                return;
            }
        }
        writeFailing = false;
        LocalDate today = budget.today();
        try {
            // 이번에 더하지 않은 키도 다른 칸이 쓴 만큼 올린다. 읽기 실패는 증가분을 되돌리지 않는다(이미 더해졌다)
            for (Map.Entry<String, Integer> row : store.load(today).entrySet()) {
                budget.applyStoredTotal(row.getKey(), today, row.getValue());
            }
        } catch (DataAccessException e) {
            report(e, "읽기 — 증가분은 이미 더했고, 다른 칸 사용량은 다음 동기화에 반영");
            return;
        }
        report(null, null);
    }

    private void report(DataAccessException failure, String what) {
        if (failure != null) {
            failureCounter.increment();
            if (!failing) {
                log.warn("공급자 예산 DB 동기화 실패({}): {}", what, failure.toString());
            }
        } else if (failing) {
            log.info("공급자 예산 DB 동기화 복구");
        }
        failing = failure != null;
    }
}
