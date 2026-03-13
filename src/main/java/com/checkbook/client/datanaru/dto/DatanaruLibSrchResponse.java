package com.checkbook.client.datanaru.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DatanaruLibSrchResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(List<LibEntry> libs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LibEntry(Lib lib) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lib(
            String libCode,
            String libName,
            String address,
            String latitude,
            String longitude,
            String region,
            String homepage
    ) {
    }
}
