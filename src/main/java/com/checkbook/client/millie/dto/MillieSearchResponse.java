package com.checkbook.client.millie.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// 외부 응답 필드는 빠지거나 null일 수 있어 래퍼 타입을 쓴다. Jackson 3은 null을 원시형에 넣으려 하면
// 예외를 던진다(FAIL_ON_NULL_FOR_PRIMITIVES 기본값 true). Jackson 2는 0/false로 채웠다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MillieSearchResponse(
        @JsonProperty("RESP_CD") Integer respCd,
        @JsonProperty("RESP_DATA") RespData respData
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespData(Content content) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<MillieBookItem> list) { }
}
