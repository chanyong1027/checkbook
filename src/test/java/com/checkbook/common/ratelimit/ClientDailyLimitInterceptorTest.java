package com.checkbook.common.ratelimit;

import com.checkbook.common.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientDailyLimitInterceptorTest {

    // 2026-10-18 23:00 KST → 자정까지 3600초
    private final DailyCounterTest.MutableClock clock =
            new DailyCounterTest.MutableClock(Instant.parse("2026-10-18T14:00:00Z"));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(true);
    }

    private MockMvc mockMvc(boolean enabled) {
        RateLimitProperties properties = new RateLimitProperties(
                enabled, new RateLimitProperties.Client(2, 1000), Map.of(), 1000);
        ClientDailyLimitInterceptor interceptor = new ClientDailyLimitInterceptor(properties, meterRegistry, clock);
        return MockMvcBuilders.standaloneSetup(new TestController())
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @RestController
    static class TestController {
        @GetMapping("/test")
        String ok() {
            return "ok";
        }
    }

    private static MockHttpServletRequestBuilder from(String ip) {
        return get("/test").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void 한도를_넘으면_429와_자정까지의_Retry_After와_JSON_본문() throws Exception {
        mockMvc.perform(from("203.0.113.7")).andExpect(status().isOk());
        mockMvc.perform(from("203.0.113.7")).andExpect(status().isOk());

        mockMvc.perform(from("203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.code").value("DAILY_REQUEST_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("오늘 조회 한도를 모두 사용했어요. 자정 이후 다시 시도해 주세요."));

        assertThat(meterRegistry.counter("ratelimit.client.rejected").count()).isEqualTo(1);
    }

    @Test
    void IP마다_따로_센다() throws Exception {
        mockMvc.perform(from("203.0.113.7"));
        mockMvc.perform(from("203.0.113.7"));

        mockMvc.perform(from("203.0.113.8")).andExpect(status().isOk());
    }

    @Test
    void IPv6는_같은_64비트_접두를_한_사용자로_센다() throws Exception {
        mockMvc.perform(from("2001:db8:1:2::1")).andExpect(status().isOk());
        mockMvc.perform(from("2001:db8:1:2:ffff::9")).andExpect(status().isOk());

        mockMvc.perform(from("2001:db8:1:2::abcd")).andExpect(status().isTooManyRequests());
        mockMvc.perform(from("2001:db8:1:3::1")).andExpect(status().isOk());
    }

    @Test
    void 꺼져_있으면_막지_않는다() throws Exception {
        MockMvc disabled = mockMvc(false);
        for (int i = 0; i < 5; i++) {
            disabled.perform(from("203.0.113.7")).andExpect(status().isOk());
        }
    }

    @Test
    void 추적_IP_상한은_설정값_그대로이고_도달은_한_번만_센다() throws Exception {
        RateLimitProperties properties = new RateLimitProperties(
                true, new RateLimitProperties.Client(1, 2), Map.of(), 1000);
        ClientDailyLimitInterceptor interceptor = new ClientDailyLimitInterceptor(properties, meterRegistry, clock);
        MockMvc small = MockMvcBuilders.standaloneSetup(new TestController())
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        small.perform(from("203.0.113.1")).andExpect(status().isOk());
        // 거절 표시가 IP 상한을 쓰면 안 된다(리뷰 R3) — 두 번째 IP도 추적돼야 한다
        small.perform(from("203.0.113.1")).andExpect(status().isTooManyRequests());
        small.perform(from("203.0.113.2")).andExpect(status().isOk());
        small.perform(from("203.0.113.2")).andExpect(status().isTooManyRequests());
        // 세 번째 IP부터는 상한이라 세지 않는다(fail-open)
        small.perform(from("203.0.113.3")).andExpect(status().isOk());
        small.perform(from("203.0.113.3")).andExpect(status().isOk());

        assertThat(meterRegistry.counter("ratelimit.client.overflow").count()).isEqualTo(1);
    }

    @Test
    void 사용자_키() {
        assertThat(ClientDailyLimitInterceptor.clientKey("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientDailyLimitInterceptor.clientKey("2001:db8:1:2::1"))
                .isEqualTo(ClientDailyLimitInterceptor.clientKey("2001:0db8:0001:0002:ffff:0:0:9"));
        // IPv4-mapped IPv6는 IPv4로 본다
        assertThat(ClientDailyLimitInterceptor.clientKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        // IP가 아닌 값(이론상 없음)은 그대로 키로 쓴다 — DNS 조회를 하지 않는다
        assertThat(ClientDailyLimitInterceptor.clientKey("unknown-host")).isEqualTo("unknown-host");
        // 16진 글자만으로 된 호스트 이름도 IP로 넘기지 않는다(리뷰 F10)
        assertThat(ClientDailyLimitInterceptor.clientKey("cafe.babe")).isEqualTo("cafe.babe");
        assertThat(ClientDailyLimitInterceptor.clientKey("1.2.3.4.5")).isEqualTo("1.2.3.4.5");
    }

    @Test
    void 로그용으로_끝자리를_가린다() {
        assertThat(ClientDailyLimitInterceptor.masked("203.0.113.7")).isEqualTo("203.0.113.x");
        assertThat(ClientDailyLimitInterceptor.masked("2001:db8:1:2::/64")).isEqualTo("2001:db8:1:x::/64");
    }
}
