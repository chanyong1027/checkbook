package com.checkbook.elibrary.contract;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtFetcherTest {

    private final RobotsTxtFetcher fetcher = new RobotsTxtFetcher(Duration.ofSeconds(2));

    private static String origin(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }

    @Test
    void 받은_규칙으로_판정하고_앱과_같은_UA를_보낸다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("User-agent: *\nDisallow: /search"));
            server.start();

            RobotsTxtFetcher.Result result = fetcher.fetch(origin(server));

            assertThat(result.reachable()).isTrue();
            assertThat(result.robots().allows("/search/searchList.ink")).isFalse();
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/robots.txt");
            assertThat(request.getHeader("User-Agent")).startsWith("Mozilla/5.0");
        }
    }

    @Test
    void 없으면_4xx_전부_허용() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.start();

            RobotsTxtFetcher.Result result = fetcher.fetch(origin(server));

            assertThat(result.reachable()).isTrue();
            assertThat(result.robots().allows("/search/searchList.ink")).isTrue();
        }
    }

    @Test
    void 서버_오류_5xx는_닿지_않음이고_서버는_있다() throws Exception {
        // RFC 9309 2.3.1.4: 5xx면 전부 막힌 것으로 본다
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.start();

            RobotsTxtFetcher.Result result = fetcher.fetch(origin(server));

            assertThat(result.reachable()).isFalse();
            assertThat(result.serverAbsent()).isFalse();
            assertThat(result.detail()).contains("503");
        }
    }

    @Test
    void 접속이_거부되면_서버가_없는_것이다() {
        RobotsTxtFetcher.Result result = fetcher.fetch("http://127.0.0.1:1");

        assertThat(result.reachable()).isFalse();
        assertThat(result.serverAbsent()).isTrue();
    }

    @Test
    void http만_받는_서버를_https로_부르면_서버가_없는_것이다() throws Exception {
        // 앱이 http 주소에 붙여 보는 https 갈래가 이 경우다 — TLS를 못 맺어 앱 검색도 닿지 않으니 막을 대상이 없다.
        // 실제 웹 서버처럼 TLS 인사말에 곧바로 평문 HTTP로 답한다(MockWebServer는 요청 줄을 기다리며 답하지 않아 다르다)
        try (ServerSocket plainHttp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket socket = plainHttp.accept()) {
                    socket.getInputStream().read(new byte[512]);
                    socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // 시험 서버 — 클라이언트가 먼저 끊어도 상관없다
                }
            });
            server.start();

            RobotsTxtFetcher.Result result = fetcher.fetch("https://127.0.0.1:" + plainHttp.getLocalPort());

            assertThat(result.reachable()).isFalse();
            assertThat(result.serverAbsent()).as(result.detail()).isTrue();
            server.join(2_000);
        }
    }

    @Test
    void 본문이_시간_안에_다_오지_않으면_예외_없이_닿지_않음이고_서버는_있다() throws Exception {
        // Jsoup은 본문을 body()에서 늦게 읽어 오류를 UncheckedIOException으로 던진다 — 새면 그 주 확인 전체가 멈춘다
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setBody("User-agent: *\n" + "# " + "x".repeat(64 * 1024))
                    .throttleBody(1024, 1, java.util.concurrent.TimeUnit.SECONDS));
            server.start();

            RobotsTxtFetcher.Result result = new RobotsTxtFetcher(Duration.ofMillis(500)).fetch(origin(server));

            assertThat(result.reachable()).isFalse();
            assertThat(result.serverAbsent()).isFalse();
        }
    }

    @Test
    void 앞_500KiB만_읽는다() throws Exception {
        // RFC 9309 2.5: 최소 500KiB는 읽어야 하고 그 뒤는 버려도 된다. 뒤에 붙은 규칙은 보지 않는다
        String filler = "# " + "x".repeat(600 * 1024) + "\n";
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("User-agent: *\n" + filler + "Disallow: /search"));
            server.start();

            RobotsTxtFetcher.Result result = fetcher.fetch(origin(server));

            assertThat(result.robots().allows("/search/searchList.ink")).isTrue();
        }
    }
}
