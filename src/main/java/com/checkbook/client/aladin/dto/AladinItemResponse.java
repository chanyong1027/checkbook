package com.checkbook.client.aladin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AladinItemResponse(List<Item> item) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String isbn13,
            String title,
            String author,
            String publisher,
            String cover,
            Long itemId,
            Integer priceSales,
            SubInfo subInfo
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubInfo(UsedList usedList) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsedList(
            UsedEntry aladinUsed,
            UsedEntry userUsed,
            UsedEntry spaceUsed
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsedEntry(Integer minPrice, Integer itemCount, String link) {
    }
}
