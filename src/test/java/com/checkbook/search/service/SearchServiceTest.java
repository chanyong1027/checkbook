package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private AladinBookService aladinBookService;

    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;

    @Mock
    private PublicLibraryRepository publicLibraryRepository;

    @Mock
    private MillieBookService millieBookService;

    private ExecutorService searchExecutor;
    private ExecutorService publicLibraryExecutor;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newFixedThreadPool(3);
        publicLibraryExecutor = Executors.newFixedThreadPool(20);
        // 기존 테스트가 밀리 호출 경로를 거치는 경우 NPE 회피용 lenient default stub.
        // 신규 케이스는 각자 명시적 stub으로 덮어씀.
        lenient().when(millieBookService.findAvailability(any()))
                .thenReturn(MillieAvailability.unavailable());
        searchService = new SearchService(
                aladinBookService,
                snapshotService,
                publicLibraryRepository,
                millieBookService,
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
        when(aladinBookService.identify(
                new InputNormalizer.NormalizedQuery("모르는책", InputNormalizer.QueryType.KEYWORD)))
                .thenReturn(Optional.empty());

        SearchResponse response = searchService.search("모르는책", null, null);

        assertThat(response.book().isbn13()).isNull();
        assertThat(response.publicLibraries()).isEmpty();
        assertThat(response.usedBook()).isNull();
        assertThat(response.newBook()).isNull();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() != SearchSection.NEW_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SKIPPED);
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.NEW_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.FAILED);
    }

    @Test
    void searchKeywordAladinSuccessReturnsSections() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        when(aladinBookService.identify(
                new InputNormalizer.NormalizedQuery("혼모노", InputNormalizer.QueryType.KEYWORD)))
                .thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743")).thenReturn(null);

        SearchResponse response = searchService.search("혼모노", null, null);

        assertThat(response.book().isbn13()).isEqualTo("9788936439743");
        assertThat(response.book().title()).isEqualTo("혼자가 혼자에게");
        assertThat(response.newBook()).isEqualTo(new SearchResponse.NewBookInfo(
                16800,
                "https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=9788936439743"
        ));
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.PUBLIC_LIBRARY)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SKIPPED);
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(status -> status.section() == SearchSection.USED_BOOK)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsOnly(SearchSectionStatus.SUCCESS);
    }

    @Test
    void searchWithLocationReturnsPublicLibraryResults() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        PublicLibrary library = PublicLibrary.builder()
                .libCode("111111")
                .name("종로도서관")
                .address("서울 종로구")
                .lat(37.57)
                .lon(126.98)
                .homepage("https://lib.example")
                .build();

        when(aladinBookService.identify(
                new InputNormalizer.NormalizedQuery("혼모노", InputNormalizer.QueryType.KEYWORD)))
                .thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743")).thenReturn(null);
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(List.of(library));
        when(snapshotService.getAvailability("9788936439743", "111111"))
                .thenReturn(new LibraryAvailabilityResult("111111", true, false, SnapshotSourceStatus.SUCCESS));

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
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        when(aladinBookService.identify(
                new InputNormalizer.NormalizedQuery("혼모노", InputNormalizer.QueryType.KEYWORD)))
                .thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743")).thenThrow(new RuntimeException("timeout"));

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
    void searchBuildsNewBookFromAladinPriceSales() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        when(aladinBookService.identify(
                new InputNormalizer.NormalizedQuery("혼모노", InputNormalizer.QueryType.KEYWORD)))
                .thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743")).thenReturn(null);

        SearchResponse response = searchService.search("혼모노", null, null);

        assertThat(response.newBook()).isEqualTo(new SearchResponse.NewBookInfo(
                16800,
                "https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=9788936439743"
        ));
    }

    // ===== 밀리 fan-out 케이스 =====

    @Test
    void millieAvailable_returnsSubscriptionWithMillieAvailable() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "테스트책", "테스트저자", "민음사", null, 15000);
        when(aladinBookService.identify(any())).thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743"))
                .thenReturn(new AladinUsedBookResult(null, null, null, null, null, null));
        when(millieBookService.findAvailability(aladinResult))
                .thenReturn(new MillieAvailability(
                        true, "ABC123",
                        "https://www.millie.co.kr/v3/book/ABC123",
                        MillieAvailability.Format.EBOOK));

        SearchResponse response = searchService.search("9788936439743", null, null);

        assertThat(response.subscription()).isNotNull();
        assertThat(response.subscription().millie()).isNotNull();
        assertThat(response.subscription().millie().available()).isTrue();
        assertThat(response.subscription().millie().bookSeq()).isEqualTo("ABC123");
        assertThat(response.subscription().millie().format()).isEqualTo(MillieAvailability.Format.EBOOK);
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.SUBSCRIPTION)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsExactly(SearchSectionStatus.SUCCESS);
    }

    @Test
    void millieUnavailable_returnsSubscriptionWithMillieUnavailable_statusSuccess() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "테스트책", "테스트저자", "민음사", null, 15000);
        when(aladinBookService.identify(any())).thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743"))
                .thenReturn(new AladinUsedBookResult(null, null, null, null, null, null));
        when(millieBookService.findAvailability(aladinResult))
                .thenReturn(MillieAvailability.unavailable());

        SearchResponse response = searchService.search("9788936439743", null, null);

        assertThat(response.subscription().millie().available()).isFalse();
        assertThat(response.subscription().millie().bookSeq()).isNull();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.SUBSCRIPTION)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsExactly(SearchSectionStatus.SUCCESS);
    }

    @Test
    void millieThrows_returnsSubscriptionFailed_withFailureDetail() {
        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "테스트책", "테스트저자", "민음사", null, 15000);
        when(aladinBookService.identify(any())).thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743"))
                .thenReturn(new AladinUsedBookResult(null, null, null, null, null, null));
        when(millieBookService.findAvailability(aladinResult))
                .thenThrow(new RuntimeException("밀리 서버 오류"));

        SearchResponse response = searchService.search("9788936439743", null, null);

        assertThat(response.subscription().millie().available()).isFalse();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.SUBSCRIPTION)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsExactly(SearchSectionStatus.FAILED);
        assertThat(response.metadata().failures())
                .filteredOn(f -> f.section() == SearchSection.SUBSCRIPTION)
                .hasSize(1);
    }

    @Test
    void aladinFailsIsbn13Null_subscriptionSkipped_wrapperPresent() {
        when(aladinBookService.identify(any())).thenReturn(Optional.empty());

        SearchResponse response = searchService.search("모르는책", null, null);

        assertThat(response.subscription()).isNotNull();
        assertThat(response.subscription().millie()).isNotNull();
        assertThat(response.subscription().millie().available()).isFalse();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.SUBSCRIPTION)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsExactly(SearchSectionStatus.SKIPPED);
    }

    @Test
    void aladinFailsIsbn13Null_doesNotCallMillieBookService() {
        when(aladinBookService.identify(any())).thenReturn(Optional.empty());

        searchService.search("모르는책", null, null);

        verify(millieBookService, never()).findAvailability(any());
    }

    @Test
    void millieSlowExceedsTotalDeadline_subscriptionFailed() {
        ReflectionTestUtils.setField(searchService, "totalDeadlineMs", 200L);

        AladinSearchResult aladinResult = new AladinSearchResult(
                "9788936439743", "테스트책", "테스트저자", "민음사", null, 15000);
        when(aladinBookService.identify(any())).thenReturn(Optional.of(aladinResult));
        when(aladinBookService.getUsedBooks("9788936439743"))
                .thenReturn(new AladinUsedBookResult(null, null, null, null, null, null));
        when(millieBookService.findAvailability(aladinResult)).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return MillieAvailability.unavailable();
        });

        SearchResponse response = searchService.search("9788936439743", null, null);

        assertThat(response.subscription().millie().available()).isFalse();
        assertThat(response.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == SearchSection.SUBSCRIPTION)
                .extracting(SearchResponse.SectionStatusDetail::status)
                .containsExactly(SearchSectionStatus.FAILED);
    }
}
