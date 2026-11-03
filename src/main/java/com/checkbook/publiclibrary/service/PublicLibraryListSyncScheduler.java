package com.checkbook.publiclibrary.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 공공도서관 목록 동기화를 기동 직후 한 번, 그 뒤 매주 돌린다. 기동 직후에도 도는 건 배포하면 바로 맞추려고다
 * (배포는 새 칸이 뜬 뒤 옛 칸을 내려서, 두 칸이 동시에 기동 동기화를 돌리지 않는다).
 * libSrch 17쪽을 순서대로 받느라 몇 초 걸려 공용 스케줄러 스레드(1개)를 쥐지 않게 자체 스레드에서 돌린다.
 * 실행기는 빈으로 등록하지 않는다(ELibraryContractCheckScheduler와 같은 이유: 실행기 빈이 생기면 Boot 기본 스케줄러가 물러난다).
 * 테스트·부하 측정 프로필은 enabled=false — 시험 DB나 스텁 응답으로 목록을 바꾸지 않게.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "public-library.list-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PublicLibraryListSyncScheduler {

    private final PublicLibraryListSynchronizer synchronizer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "public-library-list-sync");
        thread.setDaemon(true);
        return thread;
    });

    public PublicLibraryListSyncScheduler(PublicLibraryListSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.execute(this::runSafely);
    }

    @Scheduled(cron = "${public-library.list-sync.cron:0 40 4 * * MON}", zone = "Asia/Seoul")
    public void trigger() {
        executor.execute(this::runSafely);
    }

    private void runSafely() {
        try {
            synchronizer.sync();
        } catch (RuntimeException e) {
            // sync()는 실패를 결과로 돌려준다. 여기까지 오면 카운터도 못 올린 것이라 '8일 넘게 성공 없음' 알림이 잡는다
            log.error("공공도서관 목록 동기화 중 예상 못 한 오류", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
