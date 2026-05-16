package com.checkbook.featured.dto;

import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;

import java.time.Instant;
import java.util.List;

public record FeaturedBooksResponse(
        FeaturedSectionType type,
        FeaturedSource source,
        List<Item> items,
        Instant lastFetchedAt,
        boolean stale
) {
    public record Item(
            String title,
            String author,
            String publisher,
            String isbn13,
            String coverUrl,
            String publishedAt
    ) {
    }
}
