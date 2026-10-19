package com.checkbook.common.config;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest는 기본으로 메트릭 익스포트를 비활성화하므로(management.defaults.metrics.export.enabled=false)
// prometheus 엔드포인트 검증에는 @AutoConfigureMetrics가 필수 (Boot 4에서 @AutoConfigureObservability가 분리·개명됨)
// Boot 4: @SpringBootTest가 TestRestTemplate 빈을 더 이상 제공하지 않아 @AutoConfigureTestRestTemplate 필요
@AutoConfigureMetrics
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsExposureIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void prometheus_엔드포인트가_JVM과_HikariCP_메트릭을_노출한다() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
        assertThat(response.getBody()).contains("hikaricp_connections");
        assertThat(response.getBody()).contains("executor_active_threads");
        assertThat(response.getBody()).contains("executor_queued_tasks");
    }

    @Test
    void 서버_요청은_SLO_경계만_외부_호출은_10ms에서_10s_사이_구간만_남긴다() {
        restTemplate.getForEntity("/api/health", String.class);
        // 이 테스트는 외부를 부르지 않으므로 RestClient 관측이 만드는 것과 같은 이름·태그로 직접 남긴다.
        // 설정(PropertiesMeterFilter)은 이름 앞부분으로 걸리므로 어디서 등록하든 같은 구간이 붙는다
        Timer.builder("http.client.requests")
                .tags("client.name", "example.com", "error", "none", "exception", "none", "method", "GET",
                        "outcome", "SUCCESS", "status", "200", "uri", "/v1/test")
                .register(meterRegistry)
                .record(Duration.ofMillis(50));

        String body = restTemplate.getForEntity("/actuator/prometheus", String.class).getBody();

        assertThat(bucketBounds(body, "http_server_requests_seconds_bucket", "uri=\"/api/health\""))
                .containsExactly(0.2, 0.7, 1.0, 2.0, 2.8);
        List<Double> clientBounds = bucketBounds(body, "http_client_requests_seconds_bucket", "client_name=\"example.com\"");
        assertThat(clientBounds).hasSizeGreaterThan(10);
        assertThat(clientBounds).allSatisfy(le -> assertThat(le).isBetween(0.01, 10.0));
    }

    @Test
    void 진행_중_요청_수_타이머에는_구간을_붙이지_않는다() {
        // 설정은 이름 앞부분으로 걸려서 http.client.requests.active(관측이 만드는 LongTaskTimer)도 그대로 물려받는다.
        // 진행 중 요청 수만 보면 되는 지표라 구간은 시리즈만 늘린다
        restTemplate.getForEntity("/api/health", String.class);
        LongTaskTimer.builder("http.client.requests.active")
                .tags("client.name", "example.com", "method", "GET", "uri", "/v1/test")
                .register(meterRegistry);

        String body = restTemplate.getForEntity("/actuator/prometheus", String.class).getBody();

        assertThat(body).contains("http_client_requests_active_seconds", "http_server_requests_active_seconds");
        assertThat(body.lines().filter(line -> line.contains("requests_active_seconds_bucket")))
                .isEmpty();
    }

    // 한 시리즈의 le 값(+Inf 제외)을 오름차순으로
    private static List<Double> bucketBounds(String body, String metric, String seriesTag) {
        Pattern le = Pattern.compile("le=\"([^\"]+)\"");
        return body.lines()
                .filter(line -> line.startsWith(metric + "{") && line.contains(seriesTag))
                .map(line -> le.matcher(line))
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .filter(value -> !value.equals("+Inf"))
                .map(Double::parseDouble)
                .sorted()
                .toList();
    }
}
