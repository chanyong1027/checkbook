package com.checkbook.client.yes24.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 카테고리 목록 응답(종합 베스트셀러·주목할 신상품). 메인 추천에 쓰는 필드만 읽는다.
 * 항목 단위 검증(ISBN·제목·성인 여부)은 호출자(추천 갱신)가 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Yes24CategoryListResponse(
        Boolean success,
        String message,
        Data data,
        String errorCode
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<Item> items) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Integer sortOrder,
            String title,
            String author,
            String publisher,
            String isbn13,
            String cover,
            String publishDate,   // yyyyMMdd
            String adultYn,
            String link
    ) { }
}
