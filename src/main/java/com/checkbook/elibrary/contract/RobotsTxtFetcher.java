package com.checkbook.elibrary.contract;

import com.checkbook.elibrary.client.KyoboELibClient;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * 출처(scheme://호스트[:포트])의 robots.txt를 가져온다. 결과 해석은 RFC 9309 2.3.1:
 * 2xx면 규칙대로, 4xx면 없음(전부 허용), 5xx·네트워크 오류면 닿지 않음(전부 막힌 것으로 본다).
 * 앱 클라이언트와 같은 Jsoup을 쓴다 — 시간 제한이 연결·읽기 전체에 걸리고(java HttpClient는 본문 읽기에 제한이 없어
 * 천천히 흘려보내는 서버에 멈출 수 있다), TLS·리다이렉트 처리가 검색과 같다.
 */
@Component
public class RobotsTxtFetcher {

    // RFC 9309 2.5: 최소 500KiB는 읽어야 하고, 그 뒤는 버려도 된다
    private static final int MAX_BYTES = 500 * 1024;

    private final int timeoutMs;

    public RobotsTxtFetcher() {
        this(Duration.ofSeconds(10));
    }

    RobotsTxtFetcher(Duration timeout) {
        this.timeoutMs = (int) timeout.toMillis();
    }

    /**
     * @param reachable   규칙을 받았거나 없음(4xx)을 확인했다
     * @param serverError 서버는 응답했지만 5xx 등으로 robots를 주지 못했다(닿지 않음 중에서도 서버는 있는 경우)
     */
    public record Result(boolean reachable, RobotsTxt robots, boolean serverError, String detail) {

        public static Result found(RobotsTxt robots) {
            return new Result(true, robots, false, "");
        }

        public static Result unreachable(String detail, boolean serverError) {
            return new Result(false, null, serverError, detail);
        }
    }

    public Result fetch(String origin) {
        try {
            // 리다이렉트는 따라간다(Jsoup 기본, RFC는 5번 이상을 권한다)
            Connection.Response response = Jsoup.connect(origin + "/robots.txt")
                    .userAgent(KyoboELibClient.USER_AGENT)
                    .timeout(timeoutMs)
                    .maxBodySize(MAX_BYTES)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.found(RobotsTxt.parse(response.body()));
            }
            if (status >= 400 && status < 500) {
                return Result.found(RobotsTxt.ALLOW_ALL);
            }
            // 5xx, 그리고 따라가지 못한 리다이렉트(3xx)
            return Result.unreachable("http " + status, true);
        } catch (IOException | IllegalArgumentException e) {
            return Result.unreachable(e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }
}
