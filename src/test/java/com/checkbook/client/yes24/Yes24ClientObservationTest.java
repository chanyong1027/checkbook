package com.checkbook.client.yes24;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주입 빌더로 만든 클라이언트는 http.client.requests로 자동 관측된다(계약 7절 '실제 지연은 따로 관측').
 * uri 태그는 템플릿이어야 한다 — ISBN 실값이 들어가면 시리즈가 책 수만큼 늘어난다.
 */
@AutoConfigureMetrics
@SpringBootTest
@ActiveProfiles("test")
class Yes24ClientObservationTest {

    private static final MockWebServer server = new MockWebServer();

    @DynamicPropertySource
    static void yes24BaseUrl(DynamicPropertyRegistry registry) throws IOException {
        server.start();
        registry.add("yes24.base-url", () -> server.url("/").toString().replaceAll("/$", ""));
    }

    @AfterAll
    static void tearDown() throws IOException {
        server.shutdown();
    }

    @Autowired
    private Yes24Client yes24Client;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void recordsHttpClientRequestsWithTemplatedUri() {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":false,\"data\":null,\"errorCode\":\"GOODS_002\"}"));

        yes24Client.lookupByIsbn("9788900000009");

        Timer timer = meterRegistry.find("http.client.requests").tag("status", "404").timer();
        assertThat(timer).as("http.client.requests 기록").isNotNull();
        assertThat(timer.getId().getTag("uri"))
                .isEqualTo("/v1/goods/itemDetail?searchType=ISBN13&query={isbn}&detail=N")
                .doesNotContain("9788900000009");
    }
}
