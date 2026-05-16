package com.checkbook.featured.service;

import com.checkbook.featured.dto.FeaturedBooksResponse;
import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedBookRepository;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FeaturedBooksReader {

    private final FeaturedBookRepository bookRepository;
    private final FeaturedSectionSnapshotRepository snapshotRepository;

    @Transactional(readOnly = true)
    public FeaturedBooksResponse read(FeaturedSectionType type) {
        FeaturedSectionSnapshot snapshot = snapshotRepository.findById(type)
                .orElseThrow(() -> new IllegalStateException(
                        "featured_section_snapshot 시드 행 없음: " + type));

        List<FeaturedBook> books = bookRepository.findBySectionTypeOrderByRankAsc(type);
        List<FeaturedBooksResponse.Item> items = books.stream()
                .map(this::toItem)
                .toList();

        boolean stale = isStale(snapshot);

        return new FeaturedBooksResponse(
                type,
                snapshot.getSource(),
                items,
                snapshot.getLastFetchedAt(),
                stale
        );
    }

    private boolean isStale(FeaturedSectionSnapshot snapshot) {
        if (snapshot.getStatus() != SnapshotStatus.SUCCESS) return true;
        if (snapshot.getExpiresAt() == null) return true;
        return snapshot.getExpiresAt().isBefore(Instant.now());
    }

    private FeaturedBooksResponse.Item toItem(FeaturedBook b) {
        return new FeaturedBooksResponse.Item(
                b.getTitle(),
                b.getAuthor(),
                b.getPublisher(),
                b.getIsbn13(),
                b.getCoverUrl(),
                b.getPublishedAt()
        );
    }
}
