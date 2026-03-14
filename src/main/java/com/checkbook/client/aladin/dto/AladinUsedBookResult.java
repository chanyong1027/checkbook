package com.checkbook.client.aladin.dto;

public record AladinUsedBookResult(
        Integer userUsedPrice,
        Integer aladinUsedPrice,
        Integer spaceUsedPrice,
        String userUsedUrl,
        String aladinUsedUrl,
        String spaceUsedUrl
) {
}
