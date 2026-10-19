package com.checkbook.featured.service;

import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * featured 섹션 스냅샷의 나이(마지막으로 받아 온 뒤 지난 시간). 갱신이 계속 실패해도 화면은 지난 목록을 보여 주므로
 * 겉으로는 안 보인다 — 나이가 TTL을 넘으면 갱신이 멈춘 것이다(7주 장애 후속 'freshness 관측').
 * 값은 DB에서 읽어 블루·그린 어느 칸이 갱신했든 같다. 한 번도 받은 적 없으면 NaN.
 * <p>
 * 게이지가 DB를 직접 읽으면 DB가 느릴 때 지표 수집 전체가 그 자리에서 막힌다(장애 순간에 모든 지표가 빠짐).
 * 그래서 1분마다 따로 읽어 두고 게이지는 메모리만 본다. 스케줄러 스레드가 배치로 바쁘면 늦게 읽히지만 시간 단위 값이라 괜찮다
 */
@Slf4j
@Component
public class FeaturedSnapshotAgeMetrics {

    private final FeaturedSectionSnapshotRepository repository;
    private final AtomicReference<Map<FeaturedSectionType, Instant>> lastFetched = new AtomicReference<>(Map.of());

    public FeaturedSnapshotAgeMetrics(FeaturedSectionSnapshotRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        for (FeaturedSectionType type : FeaturedSectionType.values()) {
            Gauge.builder("featured.snapshot.age", this, metrics -> metrics.ageSeconds(type))
                    .description("featured 섹션 스냅샷을 마지막으로 받아 온 뒤 지난 시간")
                    .baseUnit("seconds")
                    .tag("type", type.name())
                    .register(meterRegistry);
        }
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    void reload() {
        try {
            Map<FeaturedSectionType, Instant> next = new EnumMap<>(FeaturedSectionType.class);
            for (FeaturedSectionSnapshot snapshot : repository.findAll()) {
                if (snapshot.getLastFetchedAt() != null) {
                    next.put(snapshot.getSectionType(), snapshot.getLastFetchedAt());
                }
            }
            lastFetched.set(next);
        } catch (Exception e) {
            // 못 읽으면 지난 값을 유지한다 — 나이는 계속 늘어나므로 갱신 멈춤은 그대로 보인다
            log.warn("featured 스냅샷 나이를 읽지 못함: {}", e.toString());
        }
    }

    double ageSeconds(FeaturedSectionType type) {
        Instant fetchedAt = lastFetched.get().get(type);
        return Objects.isNull(fetchedAt)
                ? Double.NaN
                : Duration.between(fetchedAt, Instant.now()).toSeconds();
    }
}
