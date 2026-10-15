package com.checkbook.client.aladin;

import com.checkbook.client.aladin.dto.AladinItemResponse;
import com.checkbook.client.aladin.dto.AladinItemResponse.Item;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class AladinClient {

    private final String ttbKey;
    private final RestClient identificationClient;
    private final RestClient listClient;

    public AladinClient(
            @Value("${aladin.base-url}") String baseUrl,
            @Value("${aladin.ttb-key}") String ttbKey,
            @Value("${aladin.identification-timeout:500}") int identificationTimeout,
            @Value("${aladin.list-timeout:10000}") int listTimeout
    ) {
        this.ttbKey = ttbKey;
        this.identificationClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(identificationTimeout))
                .build();
        // 목록(ItemList)은 featured 배치 전용 — 사용자 응답 예산(2.8s)과 무관하므로
        // 식별 타임아웃(identification-timeout)을 빌려 쓰지 않는다 (datanaru.list-timeout과 같은 패턴).
        // 실측: BESTSELLER 응답이 2s를 넘겨 워밍업 갱신 실패 (2026-07-12)
        this.listClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(listTimeout))
                .build();
    }

    public Optional<AladinSearchResult> searchBook(String query) {
        try {
            AladinItemResponse response = identificationClient.get()
                    .uri("/ItemSearch.aspx?ttbkey={key}&Query={query}&QueryType=Keyword&MaxResults=1&SearchTarget=Book&output=js&Version=20131101",
                            ttbKey, query)
                    .retrieve()
                    .body(AladinItemResponse.class);
            return extractFirst(response);
        } catch (Exception e) {
            log.error("알라딘 ItemSearch 실패: query={}", query, e);
            return Optional.empty();
        }
    }

    public Optional<AladinSearchResult> lookupBook(String isbn13) {
        try {
            AladinItemResponse response = identificationClient.get()
                    .uri("/ItemLookUp.aspx?ttbkey={key}&ItemId={isbn13}&ItemIdType=ISBN13&output=js&Version=20131101",
                            ttbKey, isbn13)
                    .retrieve()
                    .body(AladinItemResponse.class);
            return extractFirst(response);
        } catch (Exception e) {
            log.error("알라딘 ItemLookUp 실패: isbn13={}", isbn13, e);
            return Optional.empty();
        }
    }

    public List<Item> itemList(AladinListQueryType queryType, int maxResults) {
        try {
            AladinItemResponse response = listClient.get()
                    .uri("/ItemList.aspx?ttbkey={key}&QueryType={qt}&MaxResults={max}&start=1&SearchTarget=Book&output=js&Version=20131101&Cover=Big",
                            ttbKey, queryType.value(), maxResults)
                    .retrieve()
                    .body(AladinItemResponse.class);

            if (response == null || response.item() == null) {
                return List.of();
            }
            return response.item();
        } catch (Exception e) {
            log.error("알라딘 ItemList 실패: queryType={}, maxResults={}", queryType, maxResults, e);
            throw new IllegalStateException("알라딘 ItemList 오류", e);
        }
    }

    private Optional<AladinSearchResult> extractFirst(AladinItemResponse response) {
        if (response == null || response.item() == null || response.item().isEmpty()) {
            return Optional.empty();
        }

        AladinItemResponse.Item item = response.item().get(0);
        return Optional.of(new AladinSearchResult(
                item.isbn13(),
                item.title(),
                item.author(),
                item.publisher(),
                item.cover(),
                item.priceSales()
        ));
    }

    private SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
