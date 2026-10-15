package com.checkbook.search.dto;

import java.util.List;

/** 가까운 알라딘 매장. 재고는 담지 않는다 — 사용자가 {@code link}에서 직접 확인한다. */
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
