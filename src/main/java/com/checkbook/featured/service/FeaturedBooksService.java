package com.checkbook.featured.service;

import com.checkbook.featured.dto.FeaturedBooksResponse;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeaturedBooksService {

    private final FeaturedBooksReader reader;

    public FeaturedBooksResponse getSection(FeaturedSectionType type) {
        return reader.read(type);
    }
}
