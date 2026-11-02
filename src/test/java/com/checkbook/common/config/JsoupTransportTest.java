package com.checkbook.common.config;

import com.checkbook.elibrary.client.ELibraryClientException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 운영과 같은 Jsoup 전송에서 시간 초과가 SocketTimeoutException으로 와 '시간 초과'로 분류되는지 실제 요청으로 본다.
 * jsoup.useHttpClient=false가 빠지면(1.21.1+ 기본 HttpClient) HttpTimeoutException이 와 이 시험이 깨진다.
 */
class JsoupTransportTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void 운영_설정은_명시값이_없을_때만_옛_전송으로_정한다() {
        String before = System.getProperty(JsoupTransport.PROPERTY);
        try {
            System.clearProperty(JsoupTransport.PROPERTY);
            JsoupTransport.useLegacyHttpUrlConnection();
            assertThat(System.getProperty(JsoupTransport.PROPERTY)).isEqualTo("false");

            System.setProperty(JsoupTransport.PROPERTY, "true");
            JsoupTransport.useLegacyHttpUrlConnection();
            assertThat(System.getProperty(JsoupTransport.PROPERTY)).isEqualTo("true");
        } finally {
            if (before == null) {
                System.clearProperty(JsoupTransport.PROPERTY);
            } else {
                System.setProperty(JsoupTransport.PROPERTY, before);
            }
        }
    }

    @Test
    void 응답이_늦으면_SocketTimeoutException이고_시간_초과로_분류된다() throws IOException {
        JsoupTransport.useLegacyHttpUrlConnection();
        server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("<html></html>").setHeadersDelay(2, TimeUnit.SECONDS));
        server.start();

        IOException thrown = catchThrowableOfType(IOException.class,
                () -> Jsoup.connect(server.url("/").toString()).timeout(300).get());

        assertThat(thrown).isInstanceOf(SocketTimeoutException.class);
        assertThat(ELibraryClientException.ofIo("x", thrown).kind()).isEqualTo(ELibraryClientException.Kind.TIMEOUT);
    }
}
