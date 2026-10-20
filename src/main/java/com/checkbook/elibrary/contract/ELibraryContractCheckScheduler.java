package com.checkbook.elibrary.contract;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 주간 계약 확인을 매주 돌린다. 확인은 자체 스레드에서 한다 — 사이트마다 robots 10초 × 2 + 검색 15초 × 갈래 3까지 걸릴 수 있어
 * 공용 스케줄러 스레드(1개)를 쥐면 featured 지표 갱신 같은 다른 작업이 몇 분씩 밀린다.
 * 실행기는 빈으로 등록하지 않는다(ExternalCallBudgetSyncer와 같은 이유: 실행기 빈이 생기면 Boot 기본 스케줄러가 물러난다).
 * 테스트·부하 측정 프로필은 enabled=false — 부하 측정 DB에서 실제 사이트로 나가지 않게.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "elibrary.contract-check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ELibraryContractCheckScheduler {

    private final ELibraryContractChecker checker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "elibrary-contract-check");
        thread.setDaemon(true);
        return thread;
    });

    public ELibraryContractCheckScheduler(ELibraryContractChecker checker) {
        this.checker = checker;
    }

    @Scheduled(cron = "${elibrary.contract-check.cron:0 17 9 * * MON}", zone = "Asia/Seoul")
    public void trigger() {
        executor.execute(this::runSafely);
    }

    private void runSafely() {
        try {
            checker.run();
        } catch (RuntimeException e) {
            // 결과 카운터가 늘지 않으니 '8일 넘게 안 돎' 알림이 잡는다. 원인은 이 로그로 본다
            log.error("전자도서관 계약 확인 중 예상 못 한 오류", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
