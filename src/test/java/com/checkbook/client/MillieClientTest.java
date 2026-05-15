package com.checkbook.client;

import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.dto.MillieBookItem;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MillieClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private MillieClient newClient() {
        return new MillieClient(baseUrl(), 2000);
    }

    private MillieClient newClientWithTimeout(int timeoutMs) {
        return new MillieClient(baseUrl(), timeoutMs);
    }

    @Test
    void parsesSuccessfulResponseAndIgnoresUnknownFields() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 0,
                          "RESP_DATA": {
                            "content": {
                              "list": [
                                {
                                  "book_seq": "ABC123",
                                  "content_name": "테스트 제목",
                                  "subtitle": "",
                                  "author": "테스트 저자",
                                  "category": "소설",
                                  "book_brand": "테스트 출판사",
                                  "is_service": true,
                                  "is_ebook_rent": true,
                                  "unexpected_field": "ignored"
                                }
                              ]
                            }
                          }
                        }
                        """));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("테스트 제목");

        assertThat(result).hasSize(1);
        MillieBookItem item = result.get(0);
        assertThat(item.bookSeq()).isEqualTo("ABC123");
        assertThat(item.contentName()).isEqualTo("테스트 제목");
        assertThat(item.author()).isEqualTo("테스트 저자");
        assertThat(item.category()).isEqualTo("소설");
        assertThat(item.bookBrand()).isEqualTo("테스트 출판사");
        assertThat(item.isService()).isTrue();
        assertThat(item.isEbookRent()).isTrue();

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getHeader("User-Agent")).startsWith("CheckBook/");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");

        HttpUrl url = request.getRequestUrl();
        assertThat(url).isNotNull();
        assertThat(url.encodedPath()).isEqualTo("/v3/search/total");
        assertThat(url.queryParameter("searchType")).isEqualTo("total");
        assertThat(url.queryParameter("keyword")).isEqualTo("테스트 제목");
        assertThat(url.queryParameter("contentlimitCount")).isEqualTo("20");
        assertThat(url.queryParameter("postlimitCount")).isEqualTo("0");
        assertThat(url.queryParameter("librarylimitCount")).isEqualTo("0");
        assertThat(url.queryParameter("startPage")).isEqualTo("1");
    }

    @Test
    void returnsEmptyWhenListIsEmpty() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 0,
                          "RESP_DATA": {
                            "content": {
                              "list": []
                            }
                          }
                        }
                        """));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("nothing");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOnEmptyBody() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(""));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("test");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenRespDataMissing() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"RESP_CD\": 0}"));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("test");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOnTimeout() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(3, TimeUnit.SECONDS));
        server.start();

        List<MillieBookItem> result = newClientWithTimeout(500).searchByTitle("slow");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOn5xx() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("test");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyOnInvalidJson() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("not json{"));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("test");

        assertThat(result).isEmpty();
    }

    @Test
    void preservesComingSoonFlags() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 0,
                          "RESP_DATA": {
                            "content": {
                              "list": [
                                {
                                  "book_seq": "PRE001",
                                  "content_name": "출시 전 도서",
                                  "subtitle": "",
                                  "author": "신간 저자",
                                  "category": "오디오북",
                                  "book_brand": "출판사",
                                  "is_service": true,
                                  "is_ebook_rent": false
                                }
                              ]
                            }
                          }
                        }
                        """));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("출시 전 도서");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isService()).isTrue();
        assertThat(result.get(0).isEbookRent()).isFalse();
    }

    @Test
    void skipsCallForNullTitle() throws Exception {
        server = new MockWebServer();
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle(null);

        assertThat(result).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void skipsCallForBlankTitle() throws Exception {
        server = new MockWebServer();
        server.start();

        List<MillieBookItem> emptyResult = newClient().searchByTitle("");
        List<MillieBookItem> whitespaceResult = newClient().searchByTitle("   ");

        assertThat(emptyResult).isEmpty();
        assertThat(whitespaceResult).isEmpty();
        assertThat(server.getRequestCount()).isZero();
    }
}
