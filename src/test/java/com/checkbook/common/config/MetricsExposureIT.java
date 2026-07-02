package com.checkbook.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest는 기본으로 메트릭 익스포트를 비활성화하므로(management.defaults.metrics.export.enabled=false)
// prometheus 엔드포인트 검증에는 @AutoConfigureObservability가 필수
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsExposureIT {

    @Autowired
    private TestRestTemplate restTemplate;

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
}
