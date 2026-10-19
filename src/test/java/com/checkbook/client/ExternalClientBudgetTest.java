package com.checkbook.client;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.DatanaruProperties;
import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.KakaoBookProperties;
import com.checkbook.client.millie.MillieClient;
import com.checkbook.client.millie.MillieProperties;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import com.checkbook.common.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공급자 하루 예산이 차단선이면 요청을 보내지 않는다(요청 제한 결정 카드 1). */
class ExternalClientBudgetTest {

    private MockWebServer server;
    private ExternalCallBudget budget;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        budget = new ExternalCallBudget(
                new RateLimitProperties(true, null, Map.of(
                        ExternalProvider.DATANARU, new RateLimitProperties.Provider(10, 0),
                        ExternalProvider.MILLIE, new RateLimitProperties.Provider(10, 0),
                        ExternalProvider.KAKAO, new RateLimitProperties.Provider(10, 0)), 1000),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    @Test
    void 정보나루_bookExist는_예산_소진이면_보내지_않는다() {
        DatanaruClient client = new DatanaruClient(
                RestClient.builder(),
                new DatanaruProperties(baseUrl(), "test-key", 1000, 1000), new SimpleMeterRegistry(), budget);

        assertThatThrownBy(() -> client.bookExist("9788936434120", "111"))
                .isInstanceOf(DailyBudgetExceededException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 정보나루_배치_목록은_예산_소진이어도_보내고_센다() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"response\":{\"docs\":[]}}"));
        DatanaruClient client = new DatanaruClient(
                RestClient.builder(),
                new DatanaruProperties(baseUrl(), "test-key", 1000, 1000), new SimpleMeterRegistry(), budget);

        client.loanItemSrch(10);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(budget.used(ExternalProvider.DATANARU)).isEqualTo(1);
    }

    @Test
    void 밀리는_예산_소진이면_보내지_않는다() {
        MillieClient client = new MillieClient(RestClient.builder(), new MillieProperties(baseUrl(), 1000, "/v3/search/total", "https://millie.example/book/"), budget);

        assertThatThrownBy(() -> client.searchByTitle("소년이 온다"))
                .isInstanceOf(DailyBudgetExceededException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void 카카오는_예산_소진이면_빈_결과로_삼키지_않고_던진다() {
        KakaoBookClient client = new KakaoBookClient(RestClient.builder(), new KakaoBookProperties(baseUrl(), "test-key", 1000), budget);

        // 다른 실패는 빈 결과로 삼키지만, 예산 소진은 사용자에게 이유를 보여야 한다
        assertThatThrownBy(() -> client.searchBooks("소년이 온다", 1, 10))
                .isInstanceOf(DailyBudgetExceededException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
