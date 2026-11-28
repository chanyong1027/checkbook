package com.checkbook.featured.scheduler;

import com.checkbook.featured.service.FeaturedBooksRefresher;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜨면 한 번도 못 받았거나 실패한 추천 섹션을 비동기로 채운다.
 * 시험 프로필은 끈다(featured.warmup.enabled=false): Flyway 시드가 있는 PostgreSQL 시험에서는 컨텍스트가 뜨자마자
 * 다른 스레드가 가짜 DatanaruClient를 불러, 시험이 같은 가짜에 반환값을 거는 순간과 겹쳤다(11-28·11-29 두 번 간헐 실패)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "featured.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
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
