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
 * 만료 시각도 함께 내보낸다 — 갱신이 안 돌았거나 계속 실패하면(실패는 만료 시각을 그대로 둔다) 만료 시각이 지나간다.
 * <p>
 * 게이지가 DB를 직접 읽으면 DB가 느릴 때 지표 수집 전체가 그 자리에서 막힌다(장애 순간에 모든 지표가 빠짐).
 * 그래서 1분마다 따로 읽어 두고 게이지는 메모리만 본다. 스케줄러 스레드가 배치로 바쁘면 늦게 읽히지만 시간 단위 값이라 괜찮다
 */
@Slf4j
@Component
public class FeaturedSnapshotAgeMetrics {

    private final FeaturedSectionSnapshotRepository repository;
    private final AtomicReference<Map<FeaturedSectionType, Times>> snapshots = new AtomicReference<>(Map.of());

    public FeaturedSnapshotAgeMetrics(FeaturedSectionSnapshotRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        for (FeaturedSectionType type : FeaturedSectionType.values()) {
            Gauge.builder("featured.snapshot.age", this, metrics -> metrics.ageSeconds(type))
                    .description("featured 섹션 스냅샷을 마지막으로 받아 온 뒤 지난 시간")
                    .baseUnit("seconds")
                    .tag("type", type.name())
                    .register(meterRegistry);
            // 알림 규칙은 TTL 숫자를 따로 적지 않고 '만료 뒤 얼마나 지났나'(time() - 이 값)로 본다.
            // TTL은 섹션마다 다르고(168h·100h) 설정에만 있어서, 규칙에 다시 적으면 설정을 바꿀 때 어긋난다
            Gauge.builder("featured.snapshot.expiry", this, metrics -> metrics.expiryEpochSeconds(type))
                    .description("featured 섹션 스냅샷이 만료되는 시각(epoch 초). 지나면 화면에 지난 목록 표시")
                    .baseUnit("seconds")
                    .tag("type", type.name())
                    .register(meterRegistry);
        }
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    void reload() {
        try {
            Map<FeaturedSectionType, Times> next = new EnumMap<>(FeaturedSectionType.class);
            for (FeaturedSectionSnapshot snapshot : repository.findAll()) {
                if (snapshot.getLastFetchedAt() != null) {
                    next.put(snapshot.getSectionType(), new Times(snapshot.getLastFetchedAt(), snapshot.getExpiresAt()));
                }
            }
            snapshots.set(next);
        } catch (Exception e) {
            // 못 읽으면 지난 값을 유지한다 — 나이는 계속 늘어나므로 갱신 멈춤은 그대로 보인다
            log.warn("featured 스냅샷 나이를 읽지 못함: {}", e.toString());
        }
    }

    double ageSeconds(FeaturedSectionType type) {
        Times times = snapshots.get().get(type);
        return Objects.isNull(times)
                ? Double.NaN
                : Duration.between(times.fetchedAt(), Instant.now()).toSeconds();
    }

    double expiryEpochSeconds(FeaturedSectionType type) {
        Times times = snapshots.get().get(type);
        return Objects.isNull(times) || Objects.isNull(times.expiresAt())
                ? Double.NaN
                : times.expiresAt().getEpochSecond();
    }

    private record Times(Instant fetchedAt, Instant expiresAt) {
    }
}
