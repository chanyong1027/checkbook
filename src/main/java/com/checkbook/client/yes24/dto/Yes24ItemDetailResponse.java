package com.checkbook.client.yes24.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

// 외부 응답 필드는 빠지거나 null일 수 있어 래퍼 타입을 쓴다 (Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Yes24ItemDetailResponse(
        Boolean success,
        String message,
        Data data,
        String errorCode
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<Item> items) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String title,
            String author,
            String publisher,
            String isbn13,
            BigDecimal salePrice,
            String link,
            String cover
    ) { }
}
