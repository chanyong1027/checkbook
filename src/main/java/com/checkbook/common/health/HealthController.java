package com.checkbook.common.health;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 감시(UptimeRobot)가 찌르는 주소. 앱이 떠 있고 DB가 답하는지만 본다.
 * 외부 공급자는 부르지 않는다(공급자 장애로 사이트 다운 알림이 오지 않게, 하루 한도도 쓰지 않게).
 * /actuator는 Nginx가 밖으로 막아 두어서 따로 둔다. 요청 제한 인터셉터 대상이 아니다(RateLimitWebConfig).
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DatabaseHealthProbe databaseProbe;

    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> health() {
        if (databaseProbe.isUp()) {
            return ResponseEntity.ok(new HealthResponse("UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN"));
    }

    public record HealthResponse(String status) {
    }
}
