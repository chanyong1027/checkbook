package com.checkbook.featured.scheduler;

import com.checkbook.featured.service.FeaturedBooksRefresher;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeaturedBooksWarmupRunner {

    private final FeaturedSectionSnapshotRepository snapshotRepository;
    private final FeaturedBooksRefresher refresher;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmupNeverFetchedSections() {
        for (FeaturedSectionType type : FeaturedSectionType.values()) {
            FeaturedSectionSnapshot snapshot = snapshotRepository.findById(type)
                    .orElseThrow(() -> new IllegalStateException(
                            "featured_section_snapshot 시드 행 없음: " + type));
            SnapshotStatus status = snapshot.getStatus();
            if (status == SnapshotStatus.NEVER_FETCHED || status == SnapshotStatus.FAILED) {
                log.info("featured 워밍업 시작: type={}, prevStatus={}", type, status);
                refresher.refreshSection(type);
            }
        }
    }
}
