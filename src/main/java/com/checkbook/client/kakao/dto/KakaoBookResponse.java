package com.checkbook.client.kakao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoBookResponse(
        Meta meta,
        List<Document> documents
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            @JsonProperty("total_count") int totalCount,
            @JsonProperty("pageable_count") int pageableCount,
            @JsonProperty("is_end") boolean isEnd
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
