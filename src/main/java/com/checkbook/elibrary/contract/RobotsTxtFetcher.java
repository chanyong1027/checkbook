package com.checkbook.elibrary.contract;

import com.checkbook.elibrary.client.KyoboELibClient;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;
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
     * @param reachable    규칙을 받았거나 없음(4xx)을 확인했다
     * @param serverAbsent 연결을 맺지 못했다(DNS 실패·접속 거부·접속 시간 초과·TLS를 맺지 못함) — 앱 검색도 그 출처에
     *                     닿지 못한다. 응답을 받다 끊긴 것(읽기 시간 초과)·5xx는 서버가 있어 false
     */
    public record Result(boolean reachable, RobotsTxt robots, boolean serverAbsent, String detail) {

        public static Result found(RobotsTxt robots) {
            return new Result(true, robots, false, "");
        }

        public static Result unreachable(String detail, boolean serverAbsent) {
            return new Result(false, null, serverAbsent, detail);
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
            return Result.unreachable("http " + status, false);
        } catch (IOException e) {
            return Result.unreachable(describe(e), serverAbsent(e));
        } catch (UncheckedIOException e) {
            // Jsoup은 본문을 body()에서 늦게 읽고, 그때 난 입출력 오류(읽기 시간 초과·끊김)를 감싸서 던진다
            return Result.unreachable(describe(e.getCause()), false);
        } catch (RuntimeException e) {
            // 주소 오류 등 — 한 사이트의 예외가 그 주 확인 전체를 멈추지 않게
            return Result.unreachable(describe(e), false);
        }
    }

    private static boolean serverAbsent(IOException e) {
        if (e instanceof SocketTimeoutException) {
            // HttpURLConnection은 접속 단계를 "connect timed out", 읽기를 "Read timed out"으로 적는다
            return e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("connect timed out");
        }
        // TLS를 맺지 못하면 HTTP 요청이 한 번도 오가지 않는다. http만 받는 포트를 https로 부르면 핸드셰이크 예외가 아니라
        // 상위의 SSLException("Unsupported or unrecognized SSL message")이 난다(10-21 실사이트 확인: ebook.gne.go.kr:9029).
        // 단, 연결을 맺은 뒤 끊긴 것도 SSLException(원인 SocketException)으로 온다 — 서버가 있으니 빼야 한다
        if (e instanceof SSLException) {
            return !(e.getCause() instanceof SocketException);
        }
        return e instanceof UnknownHostException || e instanceof ConnectException
                || e instanceof NoRouteToHostException;
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
