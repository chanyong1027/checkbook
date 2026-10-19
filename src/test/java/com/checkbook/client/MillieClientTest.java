package com.checkbook.client;

import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.MillieClientException;
import com.checkbook.client.millie.MillieProperties;
import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return newClient(2000, "/v3/search/total");
    }

    private MillieClient newClientWithTimeout(int timeoutMs) {
        return newClient(timeoutMs, "/v3/search/total");
    }

    private MillieClient newClient(int timeoutMs, String searchPath) {
        return new MillieClient(
                RestClient.builder(),
                new MillieProperties(baseUrl(), timeoutMs, searchPath, "https://millie.example/book/"),
                ExternalCallBudget.unlimited());
    }

    @Test
    void parsesSuccessfulResponseAndIgnoresUnknownFields() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 200,
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

    // 실제 0건 응답(10-15 실호출 확인): RESP_CD 200 + content.list [] + total 0. 이것만 정상 빈 결과다
    @Test
    void returnsEmptyWhenListIsEmpty() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 200,
                          "RESP_MSG": "성공",
                          "RESP_DATA": {
                            "content": {
                              "list": [],
                              "total": 0
                            }
                          }
                        }
                        """));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("nothing");

        assertThat(result).isEmpty();
    }

    // 이전에는 아래 경우 모두 빈 목록("밀리에 없음")을 돌려줘 장애와 '없음'이 섞였다.
    // 실제 0건 응답은 구조가 모두 있으므로(위 테스트), 구조가 빠지거나 코드가 다르면 실패다
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "",                                                                  // 빈 body
            "{\"RESP_CD\": 200}",                                                // RESP_DATA 없음
            "{\"RESP_CD\": 200, \"RESP_DATA\": {}}",                             // content 없음
            "{\"RESP_CD\": 200, \"RESP_DATA\": {\"content\": {}}}",              // list 없음
            "{\"RESP_DATA\": {\"content\": {\"list\": []}}}",                      // RESP_CD 없음
            "{\"RESP_CD\": 500, \"RESP_MSG\": \"실패\", \"RESP_DATA\": {\"content\": {\"list\": []}}}", // 오류 코드
            "not json{"                                                          // JSON 아님
    })
    void throwsOnMalformedOrErrorResponse(String body) throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
        server.start();

        assertThatThrownBy(() -> newClient().searchByTitle("test"))
                .isInstanceOf(MillieClientException.class);
    }

    @Test
    void throwsOnTimeoutKeepingTimeoutCause() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(3, TimeUnit.SECONDS));
        server.start();

        // 섹션 사유(TIMEOUT)를 고르려면 원인 체인에 소켓 타임아웃이 남아 있어야 한다
        assertThatThrownBy(() -> newClientWithTimeout(500).searchByTitle("slow"))
                .isInstanceOf(MillieClientException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }

    @Test
    void throwsOnHeadersTimeoutKeepingTimeoutCause() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setHeadersDelay(3, TimeUnit.SECONDS));
        server.start();

        assertThatThrownBy(() -> newClientWithTimeout(500).searchByTitle("slow"))
                .isInstanceOf(MillieClientException.class)
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
    }

    // 제목의 중괄호·+·&는 템플릿·쿼리 문법이 아니라 글자 그대로 보내야 한다
    @Test
    void sendsTitleWithTemplateAndQuerySpecialCharactersLiterally() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"RESP_CD\": 200, \"RESP_DATA\": {\"content\": {\"list\": []}}}"));
        server.start();

        assertThat(newClient().searchByTitle("{x} C++ & 자바")).isEmpty();

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request.getRequestUrl().queryParameter("keyword")).isEqualTo("{x} C++ & 자바");
        assertThat(request.getRequestUrl().queryParameter("startPage")).isEqualTo("1");
    }

    @Test
    void throwsOn5xx() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("internal error"));
        server.start();

        assertThatThrownBy(() -> newClient().searchByTitle("test"))
                .isInstanceOf(MillieClientException.class)
                .hasMessageNotContaining("internal error");   // 메시지는 응답 failures[].reason으로 나간다 — 원문 노출 금지
    }

    @Test
    void preservesComingSoonFlags() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 200,
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

    // 밀리가 경로 버전을 바꾸면 설정만 고쳐 재배포한다 — 경로가 코드에 박혀 있지 않은지 확인
    @Test
    void usesSearchPathFromProperties() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"RESP_CD\": 200, \"RESP_DATA\": {\"content\": {\"list\": []}}}"));
        server.start();

        newClient(2000, "/v9/search/total").searchByTitle("테스트");

        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo("/v9/search/total");
        assertThat(request.getRequestUrl().queryParameter("keyword")).isEqualTo("테스트");
    }

    // Jackson 3 회귀: 값이 빠진 필드가 원시형이면 파싱 전체가 실패한다(FAIL_ON_NULL_FOR_PRIMITIVES).
    // 항목의 선택 필드가 빠져도 항목을 버리지 않는다 (응답 코드 누락은 이제 구조 오류 — 위 테스트)
    @Test
    void parsesResponseWithMissingItemFlagsInsteadOfDroppingIt() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "RESP_CD": 200,
                          "RESP_DATA": {
                            "content": {
                              "list": [
                                { "book_seq": "X1", "content_name": "플래그 누락 도서", "is_service": true }
                              ]
                            }
                          }
                        }
                        """));
        server.start();

        List<MillieBookItem> result = newClient().searchByTitle("플래그 누락 도서");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isEbookRent()).isNull();
        assertThat(result.get(0).isAvailable()).isFalse();
    }
}
