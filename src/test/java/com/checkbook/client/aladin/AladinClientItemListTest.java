package com.checkbook.client.aladin;

import com.checkbook.client.aladin.dto.AladinItemResponse.Item;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AladinClientItemListTest {

    private MockWebServer server;
    private AladinClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new AladinClient(server.url("/").toString(), "TESTKEY", 500, 2000, 2000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void itemList_bestseller_parsesPubDateAndCover() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "item": [
                            {
                              "isbn13": "9788936434120",
                              "title": "소년이 온다",
                              "author": "한강",
                              "publisher": "창비",
                              "cover": "https://cover.example/1.jpg",
                              "pubDate": "2014-05-19",
                              "itemId": 12345,
                              "priceSales": 13500
                            }
                          ]
                        }
                        """));

        List<Item> result = client.itemList(AladinListQueryType.BESTSELLER, 15);

        assertThat(result).hasSize(1);
        Item first = result.get(0);
        assertThat(first.isbn13()).isEqualTo("9788936434120");
        assertThat(first.pubDate()).isEqualTo("2014-05-19");
        assertThat(first.cover()).isEqualTo("https://cover.example/1.jpg");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("QueryType=Bestseller");
        assertThat(request.getPath()).contains("MaxResults=15");
    }

    @Test
    void itemList_itemNewSpecial_sendsCorrectQueryType() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"item\":[]}"));

        client.itemList(AladinListQueryType.ITEM_NEW_SPECIAL, 15);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("QueryType=ItemNewSpecial");
    }

    @Test
    void itemList_emptyResponse_returnsEmptyList() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"item\":[]}"));

        List<Item> result = client.itemList(AladinListQueryType.BESTSELLER, 15);

        assertThat(result).isEmpty();
    }

    @Test
    void itemList_httpError_throwsIllegalStateException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.itemList(AladinListQueryType.BESTSELLER, 15)
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void itemList_usesListTimeout_notSearchTimeout() throws Exception {
        // 검색용 timeout(100ms)보다 느리지만 list-timeout(2000ms) 안인 응답 —
        // itemList가 검색 클라이언트의 타임아웃을 빌려 쓰면 실패한다 (2026-07-12 장애 회귀 가드)
        AladinClient slowTolerantClient = new AladinClient(
                server.url("/").toString(), "TESTKEY", 100, 100, 2000);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"item\":[]}")
                .setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS));

        List<Item> result = slowTolerantClient.itemList(AladinListQueryType.BESTSELLER, 15);

        assertThat(result).isEmpty();
    }
}
