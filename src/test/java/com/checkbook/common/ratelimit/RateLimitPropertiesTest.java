package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    private static RateLimitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("rate-limit", RateLimitProperties.class);
    }

    @Test
    void 설정이_없으면_안전한_기본값() {
        RateLimitProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.client().dailyLimit()).isEqualTo(500);
        assertThat(properties.client().maxTrackedClients()).isEqualTo(200_000);
        assertThat(properties.elibraryPerLibraryLimit()).isEqualTo(1000);
        assertThat(properties.providers()).isEmpty();
    }

    @Test
    void block_at이_빠지면_하루_한도로_막는다() {
        // 빠졌을 때 0이 되면 그 공급자 호출이 전부 막힌다(리뷰 R2)
        RateLimitProperties properties = bind(Map.of("rate-limit.providers.kakao.daily-limit", "30000"));

        assertThat(properties.providers().get(ExternalProvider.KAKAO).blockAt()).isEqualTo(30000);
    }

    @Test
    void 잘못된_공급자_설정은_기동을_멈춘다() {
        assertThatThrownBy(() -> new RateLimitProperties.Provider(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Provider(100, 101))
                .isInstanceOf(IllegalArgumentException.class);
        // 실제 기동 경로(바인딩)에서도 예외로 멈춘다
        assertThatThrownBy(() -> bind(Map.of("rate-limit.providers.kakao.daily-limit", "0")))
                .isInstanceOf(BindException.class);
    }
}
