package com.checkbook.search.dto;

import java.util.List;

public record OffStoreResponse(List<StoreInfo> stores) {

    public record StoreInfo(
            String storeName,
            String address,
            Double distance,
            String link,
            Double latitude,
            Double longitude
    ) {
    }
}
