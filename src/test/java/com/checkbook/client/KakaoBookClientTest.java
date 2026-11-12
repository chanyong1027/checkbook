package com.checkbook.client;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.KakaoBookProperties;
import com.checkbook.client.kakao.KakaoSearchUnavailableException;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KakaoBookClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    // Jackson 3 회귀: meta의 원시형 필드가 빠지면 파싱 전체가 실패하고, 클라이언트가 예외를 삼켜
    // 후보 목록이 통째로 비는 경로를 막는다
    @Test
    void parsesDocumentsEvenWhenMetaFieldsAreMissing() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "meta": { "total_count": 1 },
                          "documents": [
                            { "title": "혼모노", "authors": ["성해나"], "publisher": "창비",
                              "isbn": "8936439743 9788936439743", "datetime": "2025-03-28T00:00:00.000+09:00" }
                          ]
                        }
                        """));
        server.start();
        KakaoBookClient client = new KakaoBookClient(
                RestClient.builder(),
                new KakaoBookProperties(server.url("/").toString().replaceAll("/$", ""), "test-key", 1000), ExternalCallBudget.unlimited());

        KakaoBookResponse response = client.searchBooks("혼모노", 1, 10);

        assertThat(response.documents()).hasSize(1);
        assertThat(response.meta().totalCount()).isEqualTo(1);
        assertThat(response.meta().isEnd()).isNull();
    }

    // 카카오가 실패하면 '결과 없음'이 아니라 실패를 올린다 — 예전에는 빈 목록으로 삼켜 화면이 "검색 결과가 없어요"였다
    @Test
    void throwsWhenKakaoRespondsWithError() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"errorType\":\"AccessDeniedError\"}"));
        server.start();

        assertThatThrownBy(() -> client().searchBooks("혼모노", 1, 10))
                .isInstanceOf(KakaoSearchUnavailableException.class);
    }

    // 0건도 meta와 documents([])를 모두 준다 — 본문이 비거나 둘 중 하나가 빠지면 형식 변경이지 '없음'이 아니다
    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "",
            "{\"documents\": []}",
            "{\"meta\": {\"total_count\": 0, \"is_end\": true}}"
    })
    void throwsWhenKakaoBodyIsEmptyOrMissingStructure(String body) throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body));
        server.start();

        assertThatThrownBy(() -> client().searchBooks("혼모노", 1, 10))
                .isInstanceOf(KakaoSearchUnavailableException.class);
    }

    // 실제 0건은 그대로 빈 목록 — 실패와 섞이지 않는다
    @Test
    void returnsEmptyListWhenKakaoFindsNothing() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        { "meta": { "total_count": 0, "pageable_count": 0, "is_end": true }, "documents": [] }
                        """));
        server.start();

        KakaoBookResponse response = client().searchBooks("없는책제목", 1, 10);

        assertThat(response.documents()).isEmpty();
        assertThat(response.meta().totalCount()).isZero();
    }

    private KakaoBookClient client() {
        return new KakaoBookClient(
                RestClient.builder(),
                new KakaoBookProperties(server.url("/").toString().replaceAll("/$", ""), "test-key", 1000),
                ExternalCallBudget.unlimited());
    }

    @Test
    void propertiesToStringMasksRestApiKey() {
        KakaoBookProperties properties = new KakaoBookProperties("https://dapi.kakao.com", "secret-key", 1000);

        assertThat(properties.toString()).doesNotContain("secret-key").contains("****");
    }
}
