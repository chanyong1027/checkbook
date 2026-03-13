package com.checkbook.client.naver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverShoppingResponse(List<NaverShoppingItem> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NaverShoppingItem(
            String mallName,
            String lprice,
            String link
    ) {
    }
}
