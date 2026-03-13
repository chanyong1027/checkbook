package com.checkbook.client.datanaru.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DatanaruBookExistResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Result result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String hasBook, String loanAvailable) {
    }
}
