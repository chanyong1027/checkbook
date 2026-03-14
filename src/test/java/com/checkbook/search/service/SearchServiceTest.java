package com.checkbook.search.service;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.naver.NaverShoppingClient;
import com.checkbook.client.naver.dto.NaverShoppingResult;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private AladinClient aladinClient;

    @Mock
    private NaverShoppingClient naverClient;

    @Mock
    private DatanaruClient datanaruClient;

    @Mock
    private PublicLibraryRepository publicLibraryRepository;

    private ExecutorService searchExecutor;
    private ExecutorService publicLibraryExecutor;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newFixedThreadPool(3);
        publicLibraryExecutor = Executors.newFixedThreadPool(20);
        searchService = new SearchService(
                aladinClient,
                naverClient,
                datanaruClient,
                publicLibraryRepository,
                searchExecutor,
                publicLibraryExecutor
        );
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
        publicLibraryExecutor.shutdownNow();
    }

    @Test
    void searchKeywordAladinFailsIsbn13NullAllSkipped() {
        when(aladinClient.searchBook("모르는책")).thenReturn(Optional.empty());

        SearchResponse response = searchService.search("모르는책", null, null);

        assertThat(response.book().isbn13()).isNull();
        assertThat(response.publicLibraries()).isEmpty();
        assertThat(response.usedBook()).isNull();
        assertThat(response.newBooks()).isEmpty();
        assertThat(response.metadata().sectionStatuses())
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SKIPPED);
    }

    @Test
    void searchKeywordAladinSuccessReturnsSections() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null);
        when(aladinClient.searchBook("혼모노")).thenReturn(Optional.of(aladinResult));
        when(aladinClient.getUsedBooks("9788936439743")).thenReturn(null);
        when(naverClient.searchNewBooks("9788936439743")).thenReturn(List.of());

        SearchResponse response = searchService.search("혼모노", null, null);

        assertThat(response.book().isbn13()).isEqualTo("9788936439743");
        assertThat(response.book().title()).isEqualTo("혼자가 혼자에게");
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.PUBLIC_LIBRARY)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SKIPPED);
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.USED_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SUCCESS);
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.NEW_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SUCCESS);
    }

    @Test
    void searchWithLocationReturnsPublicLibraryResults() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null);
        PublicLibrary library = PublicLibrary.builder()
                .libCode("111111")
                .name("종로도서관")
                .address("서울 종로구")
                .lat(37.57)
                .lon(126.98)
                .homepage("https://lib.example")
                .build();

        when(aladinClient.searchBook("혼모노")).thenReturn(Optional.of(aladinResult));
        when(aladinClient.getUsedBooks("9788936439743")).thenReturn(null);
        when(naverClient.searchNewBooks("9788936439743"))
                .thenReturn(List.of(new NaverShoppingResult("몰A", 12000, "https://mall.example")));
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(List.of(library));
        when(datanaruClient.bookExist("9788936439743", "111111"))
                .thenReturn(new DatanaruBookExistResult("111111", true, false));

        SearchResponse response = searchService.search("혼모노", 37.5665, 126.9780);

        assertThat(response.publicLibraries()).hasSize(1);
        assertThat(response.publicLibraries().get(0).libraryName()).isEqualTo("종로도서관");
        assertThat(response.publicLibraries().get(0).hasBook()).isTrue();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.PUBLIC_LIBRARY)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SUCCESS);
    }

    @Test
    void searchOnlyLatProvidedThrowsInvalidLocation() {
        assertThatThrownBy(() -> searchService.search("자바", 37.5665, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_LOCATION);
    }

    @Test
    void searchLatOutOfRangeThrowsInvalidLocation() {
        assertThatThrownBy(() -> searchService.search("자바", 91.0, 126.9780))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_LOCATION);
    }

    @Test
    void searchUsedBookClientThrowsSectionIsFailed() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null);
        when(aladinClient.searchBook("혼모노")).thenReturn(Optional.of(aladinResult));
        when(aladinClient.getUsedBooks("9788936439743")).thenThrow(new RuntimeException("timeout"));
        when(naverClient.searchNewBooks("9788936439743")).thenReturn(List.of());

        SearchResponse response = searchService.search("혼모노", null, null);

        assertThat(response.usedBook()).isNull();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.USED_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.FAILED);
        assertThat(response.metadata().failures())
                .extracting(SearchResponse.FailureDetail::section)
                .contains(SearchSection.USED_BOOK);
    }

    @Test
    void searchNewBookClientThrowsSectionIsFailed() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null);
        when(aladinClient.searchBook("혼모노")).thenReturn(Optional.of(aladinResult));
        when(aladinClient.getUsedBooks("9788936439743")).thenReturn(null);
        when(naverClient.searchNewBooks("9788936439743")).thenThrow(new RuntimeException("naver timeout"));

        SearchResponse response = searchService.search("혼모노", null, null);

        assertThat(response.newBooks()).isEmpty();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.NEW_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.FAILED);
        assertThat(response.metadata().failures())
                .extracting(SearchResponse.FailureDetail::section)
                .contains(SearchSection.NEW_BOOK);
    }
}
