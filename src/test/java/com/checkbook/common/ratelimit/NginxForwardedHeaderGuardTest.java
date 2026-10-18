package com.checkbook.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP별 한도는 앱이 X-Forwarded-For를 믿는 것(forward-headers-strategy: framework)에 기댄다.
 * 그 전제는 Nginx가 사용자가 보낸 값을 덧붙이지 않고 덮어쓰는 것이다 — 덧붙이면 값을 바꿔 가며 한도를 피할 수 있다.
 */
class NginxForwardedHeaderGuardTest {

    @Test
    void Nginx는_X_Forwarded_For를_덮어쓴다() throws IOException {
        String conf = Files.readString(Path.of("deploy/nginx/proxy-app.conf"));

        assertThat(conf).containsPattern("proxy_set_header\\s+X-Forwarded-For\\s+\\$remote_addr;");
        assertThat(conf).doesNotContain("$proxy_add_x_forwarded_for");
        // Spring은 Forwarded를 X-Forwarded-For보다 먼저 읽는다 — 사용자가 보낸 Forwarded를 지워야 한다(리뷰 F12)
        assertThat(conf).containsPattern("proxy_set_header\\s+Forwarded\\s+\"\";");
    }
}
