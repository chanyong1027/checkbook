package com.checkbook.client.aladin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AladinOffStoreResponse(List<OffStoreInfo> itemOffStoreList) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OffStoreInfo(
            String offCode,
            String offName,
            String link
    ) {
    }
}
