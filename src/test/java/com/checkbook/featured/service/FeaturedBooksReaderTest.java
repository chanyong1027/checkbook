package com.checkbook.featured.service;

import com.checkbook.featured.dto.FeaturedBooksResponse;
import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedBookRepository;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeaturedBooksReaderTest {

    @Mock FeaturedBookRepository bookRepository;
    @Mock FeaturedSectionSnapshotRepository snapshotRepository;
    @InjectMocks FeaturedBooksReader reader;

    private FeaturedSectionSnapshot snapshot(SnapshotStatus status, Instant expiresAt) {
        return FeaturedSectionSnapshot.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .source(FeaturedSource.ALADIN)
                .status(status)
                .lastFetchedAt(status == SnapshotStatus.NEVER_FETCHED ? null : Instant.now())
                .expiresAt(expiresAt)
                .build();
    }

    private FeaturedBook book(int rank) {
        return FeaturedBook.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .rank(rank).isbn13("978000000000" + rank)
                .title("t" + rank).author("a").publisher("p")
                .coverUrl("c").publishedAt("2025-01-01")
                .build();
    }

    @Test
    void read_success_andFresh_staleFalse() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.of(snapshot(SnapshotStatus.SUCCESS,
                        Instant.now().plusSeconds(3600))));
        when(bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER))
                .thenReturn(List.of(book(1), book(2)));

        FeaturedBooksResponse res = reader.read(FeaturedSectionType.BESTSELLER);

        assertThat(res.type()).isEqualTo(FeaturedSectionType.BESTSELLER);
        assertThat(res.source()).isEqualTo(FeaturedSource.ALADIN);
        assertThat(res.items()).hasSize(2);
        assertThat(res.items().get(0).isbn13()).isEqualTo("9780000000001");
        assertThat(res.stale()).isFalse();
    }

    @Test
    void read_success_butExpired_staleTrue() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.of(snapshot(SnapshotStatus.SUCCESS,
                        Instant.now().minusSeconds(60))));
        when(bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER))
                .thenReturn(List.of(book(1)));

        assertThat(reader.read(FeaturedSectionType.BESTSELLER).stale()).isTrue();
    }

    @Test
    void read_failed_staleTrue_butKeepsItems() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.of(snapshot(SnapshotStatus.FAILED,
                        Instant.now().plusSeconds(3600))));
        when(bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER))
                .thenReturn(List.of(book(1)));

        FeaturedBooksResponse res = reader.read(FeaturedSectionType.BESTSELLER);
        assertThat(res.stale()).isTrue();
        assertThat(res.items()).hasSize(1);
    }

    @Test
    void read_neverFetched_staleTrue_emptyItems() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.of(snapshot(SnapshotStatus.NEVER_FETCHED, null)));
        when(bookRepository.findBySectionTypeOrderByRankAsc(FeaturedSectionType.BESTSELLER))
                .thenReturn(List.of());

        FeaturedBooksResponse res = reader.read(FeaturedSectionType.BESTSELLER);
        assertThat(res.stale()).isTrue();
        assertThat(res.items()).isEmpty();
        assertThat(res.lastFetchedAt()).isNull();
    }

    @Test
    void read_snapshotRowMissing_throws() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> reader.read(FeaturedSectionType.BESTSELLER)
        ).isInstanceOf(IllegalStateException.class);
    }
}
