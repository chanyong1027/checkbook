package com.checkbook.client.millie.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// 외부 응답 필드는 빠지거나 null일 수 있어 래퍼 타입을 쓴다. Jackson 3은 null을 원시형에 넣으려 하면
// 예외를 던진다(FAIL_ON_NULL_FOR_PRIMITIVES 기본값 true). Jackson 2는 0/false로 채웠다.
// 판정은 isAvailable()로 한다(null = 불가).
@JsonIgnoreProperties(ignoreUnknown = true)
public record MillieBookItem(
        @JsonProperty("book_seq") String bookSeq,
        @JsonProperty("content_name") String contentName,
        String subtitle,
        String author,
        String category,
        @JsonProperty("book_brand") String bookBrand,
        @JsonProperty("is_service") Boolean isService,
        @JsonProperty("is_ebook_rent") Boolean isEbookRent
) {

    /** 구독으로 지금 읽을 수 있는지. 두 플래그 중 하나라도 없으면(null) 불가로 본다 — Jackson 2 시절 false 기본값과 같은 의미 */
    public boolean isAvailable() {
        return Boolean.TRUE.equals(isService) && Boolean.TRUE.equals(isEbookRent);
    }
}
