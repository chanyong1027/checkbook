package com.checkbook.client.kakao;

import com.checkbook.client.kakao.dto.KakaoBookResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class KakaoBookClient {

    private final RestClient restClient;

    public KakaoBookClient(
            @Value("${kakao.book.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${kakao.book.rest-api-key}") String restApiKey,
            @Value("${kakao.book.timeout:3000}") int timeoutMs
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .requestFactory(factory)
                .build();
    }

    public KakaoBookResponse searchBooks(String query, int page, int size) {
        try {
            KakaoBookResponse response = restClient.get()
                    .uri("/v3/search/book?query={query}&sort=accuracy&page={page}&size={size}",
                            query, page, size)
                    .retrieve()
                    .body(KakaoBookResponse.class);

            return response != null ? response : emptyResponse();
        } catch (Exception exception) {
            log.error("카카오 도서 검색 실패: query={}, page={}", query, page, exception);
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
