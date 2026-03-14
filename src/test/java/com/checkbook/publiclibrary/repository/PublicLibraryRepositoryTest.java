package com.checkbook.publiclibrary.repository;

import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicLibraryRepositoryTest {

    private final PublicLibraryRepository repository =
            mock(PublicLibraryRepository.class, Answers.CALLS_REAL_METHODS);

    @Test
    void findNearestReturnsClosestFirst() {
        List<PublicLibrary> libraries = List.of(
                PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build(),
                PublicLibrary.builder()
                .libCode("L2")
                .name("부산도서관")
                .lat(35.160)
                .lon(129.160)
                .build()
        );

        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(libraries);

        List<PublicLibrary> result = repository.findNearest(37.5666, 126.9784, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("종로도서관");
    }

    @Test
    void findNearestReturnsEmptyWhenLimitIsZero() {
        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build()));

        assertThat(repository.findNearest(37.5666, 126.9784, 0)).isEmpty();
    }

    @Test
    void findNearestExpandsBoundingBoxUntilCandidatesFound() {
        PublicLibrary library = PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build();

        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(library));

        List<PublicLibrary> result = repository.findNearest(37.5666, 126.9784, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("종로도서관");
    }
}
