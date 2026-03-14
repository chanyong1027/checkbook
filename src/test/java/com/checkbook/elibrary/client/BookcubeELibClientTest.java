package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/product/list/", exchange -> respond(exchange,
                """
                        <html>
                          <body>
                            <ul class="list typelist"></ul>
                          </body>
                        </html>
                        """));
        server.start();

        try {
            assertThat(client.search("http://127.0.0.1:" + server.getAddress().getPort(), "자바")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchInvalidDomThrowsException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/product/list/", exchange -> respond(exchange,
                """
                        <html>
                          <body>
                            <div class="unexpected"></div>
                          </body>
                        </html>
                        """));
        server.start();

        try {
            assertThatThrownBy(() -> client.search("http://127.0.0.1:" + server.getAddress().getPort(), "자바"))
                    .isInstanceOf(ELibraryClientException.class)
                    .hasMessageContaining("DOM");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchNoResultMessageWithoutResultListReturnsEmptyList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/product/list/", exchange -> respond(exchange,
                """
                        <html>
                          <body>
                            <div class="search_empty">검색 결과가 없습니다.</div>
                          </body>
                        </html>
                        """));
        server.start();

        try {
            assertThat(client.search("http://127.0.0.1:" + server.getAddress().getPort(), "자바")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
