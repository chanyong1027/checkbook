package com.checkbook.client;

import com.checkbook.client.naver.NaverShoppingClient;
import com.checkbook.client.naver.dto.NaverShoppingResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NaverShoppingClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void searchNewBooksParsesItemsAndFiltersInvalidPrice() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
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
    void searchNewBooksOnFailureThrowsException() {
        NaverShoppingClient client = new NaverShoppingClient(
                "http://127.0.0.1:1/v1/search/shop.json",
                "test-id",
                "test-secret",
                50
        );

        assertThatThrownBy(() -> client.searchNewBooks("9788936439743"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("네이버 쇼핑 API 오류");
    }

    private String baseUrl(String path) {
        return server.url(path).toString();
    }
}
