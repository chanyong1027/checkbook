package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

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

    @Test
    void implementsELibClient() {
        assertThat(client).isInstanceOf(ELibClient.class);
    }
}
