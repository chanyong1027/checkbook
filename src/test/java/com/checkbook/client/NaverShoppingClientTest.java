package com.checkbook.client;

import com.checkbook.client.naver.NaverShoppingClient;
import com.checkbook.client.naver.dto.NaverShoppingResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaverShoppingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchNewBooksParsesItemsAndFiltersInvalidPrice() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/search/shop.json", exchange -> respond(exchange,
                """
                        {
                          "items": [
                            { "mallName": "몰A", "lprice": "12000", "link": "https://mall-a.example" },
                            { "mallName": "몰B", "lprice": "not-number", "link": "https://mall-b.example" }
                          ]
                        }
                        """));
        server.start();

        NaverShoppingClient client = new NaverShoppingClient(
                baseUrl("/v1/search/shop.json"),
                "test-id",
                "test-secret",
                200
        );

        List<NaverShoppingResult> result = client.searchNewBooks("9788936439743");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).mallName()).isEqualTo("몰A");
        assertThat(result.get(0).price()).isEqualTo(12000);
    }

    @Test
    void searchNewBooksOnFailureReturnsEmptyList() {
        NaverShoppingClient client = new NaverShoppingClient(
                "http://127.0.0.1:1/v1/search/shop.json",
                "test-id",
                "test-secret",
                50
        );

        assertThat(client.searchNewBooks("9788936439743")).isEmpty();
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
