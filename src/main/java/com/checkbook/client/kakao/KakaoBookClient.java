package com.checkbook.client.kakao;

import com.checkbook.client.kakao.dto.KakaoBookResponse;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    /**
     * 실제 0건은 빈 documents로, 호출 실패·빈 본문은 {@link KakaoSearchUnavailableException}(→ 503)으로 돌려준다.
     * 예전에는 실패도 빈 목록으로 삼켜 카카오가 끊기면 모든 검색이 "검색 결과가 없어요"로 보였다(T5 재현, 결정 2026-11-10 카드 1)
     */
    public KakaoBookResponse searchBooks(String query, int page, int size) {
        // 예산 소진은 빈 결과로 삼키지 않는다 — 503과 안내 문구로 이유를 보인다
        budget.acquire(ExternalProvider.KAKAO);
        KakaoBookResponse response;
        try {
            response = restClient.get()
                    .uri("/v3/search/book?query={query}&sort=accuracy&page={page}&size={size}",
                            query, page, size)
                    .retrieve()
                    .body(KakaoBookResponse.class);
        } catch (Exception exception) {
            // 공급자 일시 실패는 사람이 고칠 일이 아니라 WARN — ERROR는 알림 대상이다(503은 5xx 알림이 잡는다)
            log.warn("카카오 도서 검색 실패: query={}, page={}", query, page, exception);
            throw new KakaoSearchUnavailableException(exception);
        }
        // 0건도 meta와 documents([])를 모두 준다. 둘 중 하나라도 없으면 '없음'이 아니라 형식이 바뀐 것이다
        if (response == null || response.meta() == null || response.documents() == null) {
            log.warn("카카오 도서 검색 응답 구조 누락: query={}, page={}", query, page);
            throw new KakaoSearchUnavailableException("카카오 도서 검색 응답 구조 누락");
        }
        return response;
    }
}
