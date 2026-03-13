package com.checkbook.client.aladin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AladinUsedListResponse(List<AladinItemResponse.Item> item) {
}
