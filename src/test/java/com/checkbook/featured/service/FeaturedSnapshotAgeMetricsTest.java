package com.checkbook.featured.service;

import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeaturedSnapshotAgeMetricsTest {

    @Test
    void 섹션마다_마지막으로_받아_온_뒤_지난_초를_내보내고_받은_적_없으면_NaN() {
        FeaturedSectionSnapshotRepository repository = mock(FeaturedSectionSnapshotRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                FeaturedSectionSnapshot.builder()
                        .sectionType(FeaturedSectionType.BESTSELLER)
                        .lastFetchedAt(Instant.now().minus(Duration.ofHours(2)))
                        .build()));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeaturedSnapshotAgeMetrics metrics = new FeaturedSnapshotAgeMetrics(repository, registry);

        metrics.reload();

        assertThat(registry.get("featured.snapshot.age").tag("type", "BESTSELLER").gauge().value())
                .isCloseTo(7200, within(5.0));
        assertThat(registry.get("featured.snapshot.age").tag("type", "LOAN").gauge().value()).isNaN();
    }

    @Test
    void DB를_못_읽으면_지난_값을_유지해_나이가_계속_늘어난다() {
        // 게이지가 DB를 직접 읽지 않으므로 DB 장애가 지표 수집 전체를 막지 않는다
        FeaturedSectionSnapshotRepository repository = mock(FeaturedSectionSnapshotRepository.class);
        when(repository.findAll())
                .thenReturn(List.of(FeaturedSectionSnapshot.builder()
                        .sectionType(FeaturedSectionType.NEW)
                        .lastFetchedAt(Instant.now().minus(Duration.ofHours(1)))
                        .build()))
                .thenThrow(new IllegalStateException("DB down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeaturedSnapshotAgeMetrics metrics = new FeaturedSnapshotAgeMetrics(repository, registry);

        metrics.reload();
        metrics.reload();

        assertThat(registry.get("featured.snapshot.age").tag("type", "NEW").gauge().value())
                .isCloseTo(3600, within(5.0));
    }
}
