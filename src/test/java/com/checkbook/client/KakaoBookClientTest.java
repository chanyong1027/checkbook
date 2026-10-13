package com.checkbook.client;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        KakaoBookClient client = new KakaoBookClient(server.url("/").toString().replaceAll("/$", ""), "test-key", 1000);

        KakaoBookResponse response = client.searchBooks("혼모노", 1, 10);

        assertThat(response.documents()).hasSize(1);
        assertThat(response.meta().totalCount()).isEqualTo(1);
        assertThat(response.meta().isEnd()).isNull();
    }
}
