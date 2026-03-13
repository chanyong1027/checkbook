package com.checkbook.client.naver.dto;

public record NaverShoppingResult(
        String mallName,
        int price,
        String productUrl
) {
}
