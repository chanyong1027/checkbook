package com.checkbook.client.millie.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MillieSearchResponse(
        @JsonProperty("RESP_CD") int respCd,
        @JsonProperty("RESP_DATA") RespData respData
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespData(Content content) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(List<MillieBookItem> list) { }
}
