package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryAvailabilitySnapshotCleaner {

    private final LibraryAvailabilitySnapshotRepository repository;

    /** 매일 새벽 3시 (KST): expires_at + 24h 여유분(총 48h)이 지난 row 삭제 */
    @Transactional
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void cleanExpiredSnapshots() {
        Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
        repository.deleteByExpiresAtBefore(cutoff);
        log.info("만료 LibraryAvailabilitySnapshot 정리 완료: cutoff={}", cutoff);
    }
}
