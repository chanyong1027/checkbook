package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KyoboELibClientTest {

    private final KyoboELibClient client = new KyoboELibClient();

    @Test
    void getVendorTypeReturnsKyobo() {
        assertThat(client.getVendorType()).isEqualTo(VendorType.KYOBO);
    }

    @Test
    void searchUnreachableUrlThrowsException() {
        assertThatThrownBy(() -> client.search("http://127.0.0.1:1", "자바"))
                .isInstanceOf(ELibraryClientException.class)
                .hasMessageContaining("접속 실패");
    }

    @Test
    void searchEmptyResultReturnsEmptyList() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("""
                            <html>
                              <body>
                                <ul class="book_resultList"></ul>
                              </body>
                            </html>
                            """));
            server.start();

            assertThat(client.search(server.url("/").toString().replaceAll("/$", ""), "자바")).isEmpty();
        }
    }

    @Test
    void 주소_끝에_슬래시가_있어도_검색_경로가_겹치지_않는다() throws Exception {
        // 끝 '/'가 남은 주소 12곳이 '//search'를 불렀다(10-20 전수 조사)
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>"));
            server.start();

            client.search(server.url("/").toString(), "자바");

            assertThat(server.takeRequest().getPath()).startsWith("/search/searchList.ink?");
        }
    }

    @Test
    void 리다이렉트를_묻는_검색은_거절되면_따라가지_않는다() throws Exception {
        // 주간 계약 확인용: 리다이렉트된 주소도 robots로 확인한 뒤에만 따라간다
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/sso/login"));
            server.start();
            String base = server.url("/").toString().replaceAll("/$", "");
            java.util.List<String> asked = new java.util.ArrayList<>();

            assertThatThrownBy(() -> client.search(base, "자바", location -> {
                asked.add(location);
                return false;
            })).isInstanceOf(KyoboELibClient.RedirectNotAllowedException.class);
            assertThat(asked).containsExactly(base + "/sso/login");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void 리다이렉트를_묻는_검색은_허락되면_따라가서_읽는다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/moved/search/searchList.ink"));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>"));
            server.start();
            String base = server.url("/").toString().replaceAll("/$", "");

            assertThat(client.search(base, "자바", location -> true)).isEmpty();
            server.takeRequest();
            assertThat(server.takeRequest().getPath()).isEqualTo("/moved/search/searchList.ink");
        }
    }

    @Test
    void 리다이렉트를_묻는_검색도_받은_세션_쿠키를_다음_요청에_싣는다() throws Exception {
        // Jsoup이 따라갈 때처럼 싣지 않으면, 세션을 심고 다시 보내는 사이트에서 끝없이 돌다 접속 실패가 된다
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Set-Cookie", "JSESSIONID=abc; Path=/")
                    .setHeader("Location", "/search/searchList.ink?again=1"));
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>"));
            server.start();

            client.search(server.url("/").toString().replaceAll("/$", ""), "자바", location -> true);

            server.takeRequest();
            assertThat(server.takeRequest().getHeader("Cookie")).contains("JSESSIONID=abc");
        }
    }

    @Test
    void 다른_호스트로_넘어가면_앞_호스트의_쿠키는_싣지_않는다() throws Exception {
        try (MockWebServer first = new MockWebServer(); MockWebServer second = new MockWebServer()) {
            second.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>"));
            second.start();
            first.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Set-Cookie", "JSESSIONID=abc; Path=/")
                    // 같은 기계지만 이름이 다르면 다른 호스트다(localhost → 127.0.0.1)
                    .setHeader("Location", "http://127.0.0.1:" + second.getPort() + "/search/searchList.ink"));
            first.start();

            client.search("http://localhost:" + first.getPort(), "자바", location -> true);

            assertThat(second.takeRequest().getHeader("Cookie")).isNull();
        }
    }

    @Test
    void 리다이렉트를_묻는_검색도_HTML이_아니면_구조_변경이다() throws Exception {
        // get()과 같은 분류여야 계약 확인의 판정이 사용자 검색과 같다
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
            server.start();

            assertThatThrownBy(() -> client.search(server.url("/").toString().replaceAll("/$", ""), "자바", location -> true))
                    .isInstanceOf(ELibraryClientException.class)
                    .satisfies(e -> assertThat(((ELibraryClientException) e).kind())
                            .isEqualTo(ELibraryClientException.Kind.STRUCTURE_CHANGED));
        }
    }

    @Test
    void searchInvalidDomThrowsException() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("""
                            <html>
                              <body>
                                <div class="unexpected"></div>
                              </body>
                            </html>
                            """));
            server.start();

            assertThatThrownBy(() -> client.search(server.url("/").toString().replaceAll("/$", ""), "자바"))
                    .isInstanceOf(ELibraryClientException.class)
                    .hasMessageContaining("DOM");
        }
    }

    @Test
    void searchNoResultMessageWithoutResultListReturnsEmptyList() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("""
                            <html>
                              <body>
                                <div class="search_empty">검색 결과가 없습니다.</div>
                              </body>
                            </html>
                            """));
            server.start();

            assertThat(client.search(server.url("/").toString().replaceAll("/$", ""), "자바")).isEmpty();
        }
    }

    // ---- 사이트 마감(T4 카드 11): 갈래·리다이렉트마다 남은 시간만 쓴다 ----

    @Test
    void 마감이_이미_지났으면_요청하지_않고_시간_초과다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();

            assertThatThrownBy(() -> client.search(base(server), "자바", System.nanoTime() - 1))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT));
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test
    void 느린_사이트는_남은_마감에서_끊긴다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(emptyResult().setHeadersDelay(3, TimeUnit.SECONDS));
            server.start();

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.search(base(server), "자바", System.nanoTime() + ms(300)))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT));
            assertThat(elapsedMs(started)).as("Jsoup 고정 15초가 아니라 남은 마감(300ms)").isLessThan(1_500);
        }
    }

    // 다음 갈래는 첫 갈래가 쓰고 남은 시간만 쓴다 — 갈래마다 새로 15초를 받던 문제(갈래 수 × 15초)
    @Test
    void 갈래는_사이트_마감을_나눠_쓴다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            // 첫 응답 대기는 남은 시간의 절반까지라(아래 시험) 700ms 마감의 첫 갈래는 350ms 안에 404를 줘야 한다
            server.enqueue(new MockResponse().setResponseCode(404).setHeadersDelay(150, TimeUnit.MILLISECONDS));
            server.enqueue(emptyResult().setHeadersDelay(3, TimeUnit.SECONDS));
            server.start();

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.search(base(server), "자바", System.nanoTime() + ms(700)))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT));
            assertThat(server.getRequestCount()).isEqualTo(2);
            assertThat(elapsedMs(started)).isLessThan(1_500);
        }
    }

    // Jsoup은 시간 제한을 연결 상한으로 쓰고, 응답을 기다리는 읽기 상한은 그 절반으로 둔다(HttpConnection: setReadTimeout(timeout / 2)).
    // 그래서 사이트 마감 5초면 첫 바이트는 2.5초까지만 기다린다 — 예전 15초일 땐 7.5초였다(T4 카드 11 구현 중 발견)
    @Test
    void 첫_응답은_남은_마감의_절반까지만_기다린다() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(emptyResult().setHeadersDelay(700, TimeUnit.MILLISECONDS));
            server.start();

            // 700ms 지연 < 1,000ms 마감이지만 절반(500ms)을 넘어 끊긴다
            assertThatThrownBy(() -> client.search(base(server), "자바", System.nanoTime() + ms(1_000)))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT));
        }
    }

    private static String base(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private static MockResponse emptyResult() {
        return new MockResponse()
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>");
    }

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    @Test
    void implementsELibClient() {
        assertThat(client).isInstanceOf(ELibClient.class);
    }
}
