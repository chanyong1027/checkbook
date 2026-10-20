package com.checkbook.common.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalCallBudgetTest {

    private final DailyCounterTest.MutableClock clock =
            new DailyCounterTest.MutableClock(Instant.parse("2026-10-18T14:59:00Z")); // 23:59 KST
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ExternalCallBudget budget(boolean enabled) {
        RateLimitProperties properties = new RateLimitProperties(
                enabled,
                new RateLimitProperties.Client(500, 1000),
                Map.of(
                        ExternalProvider.YES24, new RateLimitProperties.Provider(10, 8),
                        ExternalProvider.DATANARU, new RateLimitProperties.Provider(10, 9)),
                3, 1, 2);
        return new ExternalCallBudget(properties, meterRegistry, clock);
    }

    @Test
    void 차단선까지_허용하고_넘으면_예외() {
        ExternalCallBudget budget = budget(true);
        for (int i = 0; i < 8; i++) {
            budget.acquire(ExternalProvider.YES24);
        }

        assertThatThrownBy(() -> budget.acquire(ExternalProvider.YES24))
                .isInstanceOf(DailyBudgetExceededException.class)
                .satisfies(e -> assertThat(((DailyBudgetExceededException) e).provider())
                        .isEqualTo(ExternalProvider.YES24));
        assertThat(budget.isExhausted(ExternalProvider.YES24)).isTrue();
        assertThat(budget.isExhausted(ExternalProvider.DATANARU)).isFalse();
        assertThat(meterRegistry.counter("external.budget.rejected", "provider", "yes24").count()).isEqualTo(1);
    }

    @Test
    void record는_차단선을_넘어도_막지_않고_센다() {
        ExternalCallBudget budget = budget(true);
        for (int i = 0; i < 12; i++) {
            budget.record(ExternalProvider.YES24);
        }

        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(12);
        assertThatThrownBy(() -> budget.acquire(ExternalProvider.YES24))
                .isInstanceOf(DailyBudgetExceededException.class);
    }

    @Test
    void 공급자가_알려_준_잔량으로_사용량을_올린다() {
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.YES24);

        // 재시작 뒤 우리 카운터는 1이지만 YES24는 잔량 2(=사용 8)라고 알려 준다
        budget.observeRemaining(ExternalProvider.YES24, 2, clock.instant());

        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(8);
        assertThatThrownBy(() -> budget.acquire(ExternalProvider.YES24))
                .isInstanceOf(DailyBudgetExceededException.class);
    }

    @Test
    void 전날_보낸_요청의_잔량은_무시한다() {
        ExternalCallBudget budget = budget(true);
        java.time.Instant sentYesterday = clock.instant();
        clock.advance(Duration.ofMinutes(2)); // KST 자정 넘김

        budget.observeRemaining(ExternalProvider.YES24, 0, sentYesterday);

        assertThat(budget.used(ExternalProvider.YES24)).isZero();
    }

    @Test
    void KST_자정에_예산이_다시_찬다() {
        ExternalCallBudget budget = budget(true);
        for (int i = 0; i < 8; i++) {
            budget.acquire(ExternalProvider.YES24);
        }

        clock.advance(Duration.ofMinutes(1));

        budget.acquire(ExternalProvider.YES24);
        assertThat(budget.used(ExternalProvider.YES24)).isEqualTo(1);
    }

    @Test
    void 설정이_없는_공급자는_세기만_하고_막지_않는다() {
        ExternalCallBudget budget = budget(true);
        for (int i = 0; i < 100; i++) {
            budget.acquire(ExternalProvider.MILLIE);
        }

        assertThat(budget.used(ExternalProvider.MILLIE)).isEqualTo(100);
    }

    @Test
    void 꺼져_있으면_막지_않는다() {
        ExternalCallBudget budget = budget(false);
        for (int i = 0; i < 20; i++) {
            budget.acquire(ExternalProvider.YES24);
            budget.acquireELibrary(1L, false);
            budget.acquireELibrary(2L, true);
        }

        assertThat(budget.isExhausted(ExternalProvider.YES24)).isFalse();
    }

    @Test
    void 전자도서관은_도서관마다_따로_센다() {
        ExternalCallBudget budget = budget(true);
        for (int i = 0; i < 3; i++) {
            budget.acquireELibrary(1L, false);
        }

        assertThatThrownBy(() -> budget.acquireELibrary(1L, false))
                .isInstanceOf(DailyBudgetExceededException.class)
                .satisfies(e -> assertThat(((DailyBudgetExceededException) e).provider())
                        .isEqualTo(ExternalProvider.ELIBRARY));
        budget.acquireELibrary(2L, false);
    }

    @Test
    void robots가_막은_사이트는_더_낮은_하루_상한을_쓴다() {
        // 사용자 1회 조회만 예외로 연 사이트라 부하를 더 줄인다(결정 2026-10-20). 이 테스트에서 일반 3, 차단 1
        ExternalCallBudget budget = budget(true);
        budget.acquireELibrary(1L, true);

        assertThatThrownBy(() -> budget.acquireELibrary(1L, true))
                .isInstanceOf(DailyBudgetExceededException.class);
        for (int i = 0; i < 3; i++) {
            budget.acquireELibrary(2L, false);
        }
    }

    @Test
    void 사용량_게이지를_내보낸다() {
        ExternalCallBudget budget = budget(true);
        budget.acquire(ExternalProvider.DATANARU);
        budget.acquire(ExternalProvider.DATANARU);

        assertThat(meterRegistry.get("external.budget.used").tag("provider", "datanaru").gauge().value())
                .isEqualTo(2.0);
    }

    @Test
    void 한도와_차단선을_게이지로_내보낸다() {
        budget(true);

        assertThat(limitGauge("yes24", "daily")).isEqualTo(10.0);
        assertThat(limitGauge("yes24", "block")).isEqualTo(8.0);
        assertThat(limitGauge("datanaru", "block")).isEqualTo(9.0);
        // 설정이 없는 공급자는 막지 않으니 차단선 알림도 없다
        assertThat(meterRegistry.find("external.budget.limit").tag("provider", "millie").gauges()).isEmpty();
    }

    @Test
    void 꺼져_있으면_한도_게이지를_내보내지_않는다() {
        budget(false);

        // 막지 않는데 차단선을 내보내면 사용량이 넘을 때 '막혔다'는 알림이 거짓으로 울린다
        assertThat(meterRegistry.find("external.budget.limit").gauges()).isEmpty();
    }

    private double limitGauge(String provider, String kind) {
        return meterRegistry.get("external.budget.limit").tag("provider", provider).tag("kind", kind).gauge().value();
    }

    @Test
    void unlimited는_아무것도_막지_않는다() {
        ExternalCallBudget budget = ExternalCallBudget.unlimited();
        for (int i = 0; i < 100_000; i++) {
            budget.acquire(ExternalProvider.KAKAO);
        }
        assertThat(budget.isExhausted(ExternalProvider.KAKAO)).isFalse();
    }
}
