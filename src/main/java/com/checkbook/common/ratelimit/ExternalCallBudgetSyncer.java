package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 공급자 하루 예산을 DB와 맞춘다(리뷰 F2: 재시작·블루그린 전환에 카운터가 0이 되던 문제).
 * 기동 직후 오늘 합계를 읽고, 10초마다 증가분을 더한 뒤 합계를 다시 읽고, 종료 때 한 번 더 쓴다.
 * DB가 응답하지 않으면 증가분을 보관해 다음에 함께 더한다(결정 카드 B1) — 그동안 요청 처리는 메모리 카운터로 계속된다.
 */
@Slf4j
@Component
public class ExternalCallBudgetSyncer {

    private final ExternalCallBudget budget;
    private final ExternalCallBudgetStore store;
    private final Counter failureCounter;
    private boolean failing;
    // 종료 flush를 건너뛸지는 쓰기 실패로만 판단한다(읽기만 실패했으면 쓰기는 되는 상태)
    private boolean writeFailing;

    public ExternalCallBudgetSyncer(ExternalCallBudget budget, ExternalCallBudgetStore store, MeterRegistry meterRegistry) {
        this.budget = budget;
        this.store = store;
        this.failureCounter = Counter.builder("external.budget.sync.failures")
                .description("공급자 하루 예산 DB 동기화 실패 횟수(실패해도 메모리로 계속 센다)")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        sync();
    }

    @Scheduled(fixedDelayString = "${rate-limit.sync-interval-ms:10000}", initialDelayString = "${rate-limit.sync-interval-ms:10000}")
    public void scheduledSync() {
        sync();
    }

    // 직전 동기화의 쓰기가 실패한 상태(DB가 죽어 있음)면 종료 때 다시 연결을 기다리지 않는다 — 기다리면 docker stop
    // 시간을 넘긴다. 이때 남은 증가분은 잃는다(결정 문서의 알려진 한계)
    @PreDestroy
    public synchronized void flushOnShutdown() {
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
