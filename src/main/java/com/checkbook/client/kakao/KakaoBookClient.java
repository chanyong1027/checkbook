package com.checkbook.client.kakao;

import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class KakaoBookClient {

    private final RestClient restClient;
    private final ExternalCallBudget budget;

    // builder는 Boot 자동 설정 빌더(prototype)라야 http.client.requests가 남는다 — RestClient.builder()로 만들면 관측이 안 붙는다
    public KakaoBookClient(RestClient.Builder builder, KakaoBookProperties properties, ExternalCallBudget budget) {
        this.budget = budget;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("Authorization", "KakaoAK " + properties.restApiKey())
                .requestFactory(factory)
                .build();
    }

    public KakaoBookResponse searchBooks(String query, int page, int size) {
        // 예산 소진은 빈 결과로 삼키지 않는다 — 503과 안내 문구로 이유를 보인다
        budget.acquire(ExternalProvider.KAKAO);
        try {
            KakaoBookResponse response = restClient.get()
                    .uri("/v3/search/book?query={query}&sort=accuracy&page={page}&size={size}",
                            query, page, size)
                    .retrieve()
                    .body(KakaoBookResponse.class);

            return response != null ? response : emptyResponse();
        } catch (Exception exception) {
            // 공급자 일시 실패는 사람이 고칠 일이 아니라 WARN — ERROR는 알림 대상이다
            log.warn("카카오 도서 검색 실패: query={}, page={}", query, page, exception);
            return emptyResponse();
        }
    }

    private KakaoBookResponse emptyResponse() {
        return new KakaoBookResponse(
                new KakaoBookResponse.Meta(0, 0, true),
                List.of()
        );
    }
}
