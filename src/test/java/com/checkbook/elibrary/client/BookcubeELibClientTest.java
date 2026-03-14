package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookcubeELibClientTest {

    private final BookcubeELibClient client = new BookcubeELibClient();

    @Test
    void getVendorTypeReturnsBookcube() {
        assertThat(client.getVendorType()).isEqualTo(VendorType.BOOKCUBE);
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
                                <ul class="list typelist"></ul>
                              </body>
                            </html>
                            """));
            server.start();

            assertThat(client.search(server.url("/").toString().replaceAll("/$", ""), "자바")).isEmpty();
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
}
