package com.checkbook.client.millie.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MillieBookItem(
        @JsonProperty("book_seq") String bookSeq,
        @JsonProperty("content_name") String contentName,
        String subtitle,
        String author,
        String category,
        @JsonProperty("book_brand") String bookBrand,
        @JsonProperty("is_service") boolean isService,
        @JsonProperty("is_ebook_rent") boolean isEbookRent
) { }
