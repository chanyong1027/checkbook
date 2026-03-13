package com.checkbook.client.datanaru.dto;

public record DatanaruLibSrchResult(
        String libCode,
        String name,
        String address,
        Double lat,
        Double lon,
        String region,
        String homepage
) {
}
