package com.checkbook.client.datanaru.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DatanaruLoanItemResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(List<DocEntry> docs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocEntry(Doc doc) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Doc(
            String ranking,
            String bookname,
            String authors,
            String publisher,
            @com.fasterxml.jackson.annotation.JsonProperty("publication_year")
            String publicationYear,
            String isbn13,
            @com.fasterxml.jackson.annotation.JsonProperty("bookImageURL")
            String bookImageUrl
    ) {
    }
}
