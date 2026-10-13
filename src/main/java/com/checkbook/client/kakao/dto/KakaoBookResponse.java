package com.checkbook.client.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoBookResponse(
        Meta meta,
        List<Document> documents
) {
    // 외부 응답 필드는 빠지거나 null일 수 있어 래퍼 타입을 쓴다. Jackson 3은 null을 원시형에 넣으려 하면
    // 예외를 던진다(FAIL_ON_NULL_FOR_PRIMITIVES 기본값 true). Jackson 2는 0/false로 채웠다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            @JsonProperty("total_count") Integer totalCount,
            @JsonProperty("pageable_count") Integer pageableCount,
            @JsonProperty("is_end") Boolean isEnd
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String title,
            List<String> authors,
            String publisher,
            String thumbnail,
            String isbn,
            String datetime
    ) {
    }
}
