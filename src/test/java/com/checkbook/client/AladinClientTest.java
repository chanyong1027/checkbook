package com.checkbook.client;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AladinClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void searchBookParsesFirstItem() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
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

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 2000, 2000);

        Optional<AladinSearchResult> result = client.searchBook("자바");

        assertThat(result).isPresent();
        assertThat(result.get().isbn13()).isEqualTo("9788936439743");
        assertThat(result.get().coverUrl()).isEqualTo("https://example.com/cover.jpg");
    }

    @Test
    void getUsedBooksParsesUsedList() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
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

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 2000, 2000);

        AladinUsedBookResult result = client.getUsedBooks("9788936439743");

        assertThat(result).isNotNull();
        assertThat(result.userUsedPrice()).isEqualTo(6500);
        assertThat(result.aladinUsedPrice()).isEqualTo(7000);
        assertThat(result.spaceUsedPrice()).isEqualTo(6000);
        assertThat(result.userUsedUrl()).endsWith("123456&TabType=1");
        assertThat(result.aladinUsedUrl()).endsWith("123456&TabType=2");
        assertThat(result.spaceUsedUrl()).endsWith("123456&TabType=3");
    }

    @Test
    void getOffStoreListParsesStores() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "itemOffStoreList": [
                            {
                              "offCode": "Jongno",
                              "offName": "종로점",
                              "link": "http://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId=123&OffCode=Jongno"
                            },
                            {
                              "offCode": "Sinchon",
                              "offName": "신촌점",
                              "link": "http://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId=123&OffCode=Sinchon"
                            }
                          ]
                        }
                        """));
        server.start();

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 2000, 2000);

        var result = client.getOffStoreList("9788936439743");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).offCode()).isEqualTo("Jongno");
        assertThat(result.get(0).offName()).isEqualTo("종로점");
        assertThat(result.get(1).offCode()).isEqualTo("Sinchon");
    }

    @Test
    void getOffStoreListEmptyResponseReturnsEmptyList() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        { "itemOffStoreList": [] }
                        """));
        server.start();

        AladinClient client = new AladinClient(baseUrl("/ttb/api"), "test-key", 2000, 2000);

        assertThat(client.getOffStoreList("9788936439743")).isEmpty();
    }

    @Test
    void getOffStoreListOnFailureThrows() {
        AladinClient client = new AladinClient("http://127.0.0.1:1/ttb/api", "test-key", 50, 50);

        assertThatThrownBy(() -> client.getOffStoreList("9788936439743"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("알라딘 매장 재고 조회 오류");
    }

    @Test
    void searchBookOnFailureReturnsEmpty() {
        AladinClient client = new AladinClient("http://127.0.0.1:1/ttb/api", "test-key", 50, 50);

        assertThat(client.searchBook("자바")).isEmpty();
        assertThat(client.lookupBook("9788936439743")).isEmpty();
        assertThatThrownBy(() -> client.getUsedBooks("9788936439743"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("알라딘 중고 조회 오류");
    }

    private String baseUrl(String path) {
        return server.url(path).toString();
    }
}
