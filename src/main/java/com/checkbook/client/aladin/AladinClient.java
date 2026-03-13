package com.checkbook.client.aladin;

import com.checkbook.client.aladin.dto.AladinItemResponse;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.client.aladin.dto.AladinUsedListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Slf4j
@Component
public class AladinClient {

    private final String ttbKey;
    private final RestClient identificationClient;
    private final RestClient priceClient;

    public AladinClient(
            @Value("${aladin.base-url}") String baseUrl,
            @Value("${aladin.ttb-key}") String ttbKey,
            @Value("${aladin.identification-timeout:500}") int identificationTimeout,
            @Value("${aladin.timeout:2000}") int timeout
    ) {
        this.ttbKey = ttbKey;
        this.identificationClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(identificationTimeout))
                .build();
        this.priceClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeout))
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

    public AladinUsedBookResult getUsedBooks(String isbn13) {
        try {
            AladinUsedListResponse response = priceClient.get()
                    .uri("/ItemLookUp.aspx?ttbkey={key}&ItemId={isbn13}&ItemIdType=ISBN13&OptResult=usedList&output=js&Version=20131101",
                            ttbKey, isbn13)
                    .retrieve()
                    .body(AladinUsedListResponse.class);

            if (response == null || response.item() == null || response.item().isEmpty()) {
                return null;
            }

            AladinItemResponse.Item item = response.item().get(0);
            if (item.subInfo() == null || item.subInfo().usedList() == null) {
                return null;
            }

            AladinItemResponse.UsedList usedList = item.subInfo().usedList();
            return new AladinUsedBookResult(
                    extractMinPrice(usedList.userUsed()),
                    extractMinPrice(usedList.aladinUsed()),
                    extractMinPrice(usedList.spaceUsed()),
                    item.itemId() != null
                            ? "https://www.aladin.co.kr/shop/wproduct.aspx?ItemId=" + item.itemId()
                            : null
            );
        } catch (Exception e) {
            log.error("알라딘 중고 조회 실패: isbn13={}", isbn13, e);
            return null;
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
                item.cover()
        ));
    }

    private Integer extractMinPrice(AladinItemResponse.UsedEntry entry) {
        return entry != null ? entry.minPrice() : null;
    }

    private SimpleClientHttpRequestFactory requestFactory(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }
}
