package com.checkbook.featured.service;

import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionSnapshot;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import com.checkbook.featured.snapshot.domain.SnapshotStatus;
import com.checkbook.featured.snapshot.repository.FeaturedBookRepository;
import com.checkbook.featured.snapshot.repository.FeaturedSectionSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeaturedBooksWriterTest {

    @Mock FeaturedBookRepository bookRepository;
    @Mock FeaturedSectionSnapshotRepository snapshotRepository;
    @InjectMocks FeaturedBooksWriter writer;

    private FeaturedBook book(int rank) {
        return FeaturedBook.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .rank(rank)
                .isbn13("978000000000" + rank)
                .title("title " + rank)
                .build();
    }

    @Test
    void replaceSection_deletesOldBooks_savesNew_marksSnapshotSuccess() {
        FeaturedSectionSnapshot snapshot = FeaturedSectionSnapshot.builder()
                .sectionType(FeaturedSectionType.BESTSELLER)
                .source(FeaturedSource.ALADIN)
                .status(SnapshotStatus.NEVER_FETCHED)
                .build();
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.of(snapshot));

        List<FeaturedBook> books = List.of(book(1), book(2));

        writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.ALADIN,
                books, Duration.ofDays(7));

        verify(bookRepository).deleteBySectionType(FeaturedSectionType.BESTSELLER);
        ArgumentCaptor<List<FeaturedBook>> captor = ArgumentCaptor.forClass(List.class);
        verify(bookRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);

        assertThat(snapshot.getStatus()).isEqualTo(SnapshotStatus.SUCCESS);
        assertThat(snapshot.getSource()).isEqualTo(FeaturedSource.ALADIN);
        assertThat(snapshot.getLastFetchedAt()).isNotNull();
        assertThat(snapshot.getExpiresAt()).isAfter(snapshot.getLastFetchedAt());
        assertThat(snapshot.getFailureReason()).isNull();
    }

    @Test
    void markFailed_preservesBooks_updatesSnapshotOnly() {
        FeaturedSectionSnapshot snapshot = FeaturedSectionSnapshot.builder()
                .sectionType(FeaturedSectionType.LOAN)
                .source(FeaturedSource.DATANARU)
                .status(SnapshotStatus.SUCCESS)
                .build();
        when(snapshotRepository.findById(FeaturedSectionType.LOAN))
                .thenReturn(Optional.of(snapshot));

        writer.markFailed(FeaturedSectionType.LOAN, "timeout after 2000ms");

        verify(bookRepository, org.mockito.Mockito.never()).deleteBySectionType(eq(FeaturedSectionType.LOAN));
        verify(bookRepository, org.mockito.Mockito.never()).saveAll(org.mockito.ArgumentMatchers.anyIterable());
        assertThat(snapshot.getStatus()).isEqualTo(SnapshotStatus.FAILED);
        assertThat(snapshot.getFailureReason()).isEqualTo("timeout after 2000ms");
    }

    @Test
    void replaceSection_missingSnapshotRow_throws() {
        when(snapshotRepository.findById(FeaturedSectionType.BESTSELLER))
                .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                writer.replaceSection(FeaturedSectionType.BESTSELLER, FeaturedSource.ALADIN,
                        List.of(), Duration.ofDays(7))
        ).isInstanceOf(IllegalStateException.class);
    }
}
