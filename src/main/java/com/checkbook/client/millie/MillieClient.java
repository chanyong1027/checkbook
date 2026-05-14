package com.checkbook.client.millie;

import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.client.millie.dto.MillieSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class MillieClient {

    private static final String USER_AGENT =
            "CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)";

    private final RestClient restClient;

    public MillieClient(
            @Value("${millie.base-url:https://live-api.millie.co.kr}") String baseUrl,
            @Value("${millie.timeout:2000}") int timeout
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", USER_AGENT)
                .requestFactory(factory)
                .build();
    }

    public List<MillieBookItem> searchByTitle(String title) {
        if (title == null || title.isBlank()) return List.of();

        try {
            MillieSearchResponse response = restClient.get()
                    .uri(builder -> builder.path("/v3/search/total")
                            .queryParam("searchType", "total")
                            .queryParam("keyword", title)
                            .queryParam("contentlimitCount", 20)
                            .queryParam("postlimitCount", 0)
                            .queryParam("librarylimitCount", 0)
                            .queryParam("startPage", 1)
                            .build())
                    .retrieve()
                    .body(MillieSearchResponse.class);

            if (response == null || response.respData() == null
                    || response.respData().content() == null
                    || response.respData().content().list() == null) {
                return List.of();
            }
            return response.respData().content().list();
        } catch (Exception e) {
            log.error("밀리 검색 실패: title={}", title, e);
            return List.of();
        }
    }
}
