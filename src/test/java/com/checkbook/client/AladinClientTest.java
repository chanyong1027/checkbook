package com.checkbook.client;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AladinClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchBookParsesFirstItem() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ttb/api/ItemSearch.aspx", exchange -> respond(exchange,
                """
                        {
                          "item": [
                            {
                              "isbn13": "9788936439743",
                              "title": "자바 프로그래밍",
                              "author": "홍길동",
                              "publisher": "한빛",
                              "cover": "https://example.com/cover.jpg",
                              "itemId": 123456
                            }
                          ]
                        }
                        """));
        server.start();

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 200, 200);

        Optional<AladinSearchResult> result = client.searchBook("자바");

        assertThat(result).isPresent();
        assertThat(result.get().isbn13()).isEqualTo("9788936439743");
        assertThat(result.get().coverUrl()).isEqualTo("https://example.com/cover.jpg");
    }

    @Test
    void getUsedBooksParsesUsedList() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/ttb/api/ItemLookUp.aspx", exchange -> respond(exchange,
                """
                        {
                          "item": [
                            {
                              "itemId": 123456,
                              "subInfo": {
                                "usedList": {
                                  "aladinUsed": { "minPrice": 7000, "itemCount": 2 },
                                  "userUsed": { "minPrice": 6500, "itemCount": 1 },
                                  "spaceUsed": { "minPrice": 6000, "itemCount": 3 }
                                }
                              }
                            }
                          ]
                        }
                        """));
        server.start();

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 200, 200);

        AladinUsedBookResult result = client.getUsedBooks("9788936439743");

        assertThat(result).isNotNull();
        assertThat(result.userUsedPrice()).isEqualTo(6500);
        assertThat(result.aladinUsedPrice()).isEqualTo(7000);
        assertThat(result.spaceUsedPrice()).isEqualTo(6000);
        assertThat(result.detailUrl()).endsWith("123456");
    }

    @Test
    void searchBookOnFailureReturnsEmpty() {
        AladinClient client = new AladinClient("http://127.0.0.1:1/ttb/api", "test-key", 50, 50);

        assertThat(client.searchBook("자바")).isEmpty();
        assertThat(client.lookupBook("9788936439743")).isEmpty();
        assertThat(client.getUsedBooks("9788936439743")).isNull();
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
