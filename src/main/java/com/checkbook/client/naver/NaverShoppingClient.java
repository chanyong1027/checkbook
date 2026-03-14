package com.checkbook.client.naver;

import com.checkbook.client.naver.dto.NaverShoppingResponse;
import com.checkbook.client.naver.dto.NaverShoppingResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class NaverShoppingClient {

    private final RestClient restClient;

    public NaverShoppingClient(
            @Value("${naver.base-url}") String baseUrl,
            @Value("${naver.client-id}") String clientId,
            @Value("${naver.client-secret}") String clientSecret,
            @Value("${naver.timeout:2000}") int timeout
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Naver-Client-Id", clientId)
                .defaultHeader("X-Naver-Client-Secret", clientSecret)
                .build();
    }

    public List<NaverShoppingResult> searchNewBooks(String isbn13) {
        try {
            NaverShoppingResponse response = restClient.get()
                    .uri("?query={query}&display=20&sort=sim", isbn13)
                    .retrieve()
                    .body(NaverShoppingResponse.class);

            if (response == null || response.items() == null) {
                return List.of();
            }

            return response.items().stream()
                    .map(item -> new NaverShoppingResult(
                            item.mallName(),
                            parsePrice(item.lprice()),
                            item.link()))
                    .filter(item -> item.price() > 0)
                    .toList();
        } catch (Exception e) {
            log.error("네이버 쇼핑 검색 실패: isbn13={}", isbn13, e);
            throw new IllegalStateException("네이버 쇼핑 API 오류", e);
        }
    }

    private int parsePrice(String price) {
        try {
            return Integer.parseInt(price);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
