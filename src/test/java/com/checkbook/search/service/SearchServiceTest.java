package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.client.millie.MillieClientException;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentificationResult.Yes24Status;
import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchResponse.SectionStatusDetail;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionReason;
import com.checkbook.search.dto.SearchSectionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.checkbook.search.service.SearchResponseConsistency.assertConsistent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 계약 3절 섹션별 매핑표, 4절 JSON 예시 3종. 모든 응답에 일관성 assertion(2절)을 건다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchServiceTest {

    private static final String ISBN = "9788900000009";
    private static final String CODE = "2004566000649";
    private static final double LAT = 37.5665;
    private static final double LON = 126.9780;

    private static final IdentifiedBook YES24_BOOK = new IdentifiedBook(
            "YES24 제목", "홍길동 저", ISBN, "YES24출판", "https://image.yes24.com/goods/1/XL");
    private static final BookHint HINT = new BookHint("힌트 제목", "힌트 저자", "힌트출판", "https://k.example/1.jpg");
    private static final IdentifiedBook HINT_BOOK = new IdentifiedBook(
            "힌트 제목", "힌트 저자", ISBN, "힌트출판", "https://k.example/1.jpg");
    private static final String YES24_URL = "https://www.yes24.com/product/goods/1";

    @Mock
    private BookIdentificationService identificationService;
    @Mock
    private AladinBookService aladinBookService;
    @Mock
    private MillieBookService millieBookService;
    @Mock
    private PublicLibraryAvailabilityService publicLibraryAvailabilityService;

    private ExecutorService searchExecutor;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newFixedThreadPool(3);
        searchService = new SearchService(
                identificationService, aladinBookService, millieBookService, publicLibraryAvailabilityService, searchExecutor);
        when(aladinBookService.getUsedBooks(ISBN)).thenReturn(new AladinUsedBookResult(6500, null, null, "https://u", null, null));
        when(millieBookService.findAvailability(any())).thenReturn(MillieAvailability.unavailable());
        when(publicLibraryAvailabilityService.fetch(ISBN, LAT, LON, 0)).thenReturn(libraryPage());
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
    }

    // ===== 입력 =====

    @Test
    void keywordIsRejectedWith400WithoutIdentifying() {
        assertThatThrownBy(() -> searchService.search("자바의 정석", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.KEYWORD_NOT_SUPPORTED);
        verifyNoInteractions(identificationService);
    }

    @Test
    void onlyLatProvidedThrowsInvalidLocation() {
        assertThatThrownBy(() -> searchService.search(ISBN, LAT, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_LOCATION);
    }

    @Test
    void latOutOfRangeThrowsInvalidLocation() {
        assertThatThrownBy(() -> searchService.search(ISBN, 91.0, LON, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_LOCATION);
    }

    // ===== 4절 JSON 예시 =====

    // (a) 정상 ISBN, YES24 찾음, 위치 있음
    @Test
    void exampleA_foundWithLocation() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(millieBookService.findAvailability(YES24_BOOK)).thenReturn(millieAvailable());

        SearchResponse r = assertConsistent(searchService.search(ISBN, LAT, LON, null));

        assertThat(r.book()).isEqualTo(new SearchResponse.BookInfo(
                "YES24 제목", "홍길동 저", ISBN, "YES24출판", "https://image.yes24.com/goods/1/XL"));
        assertThat(r.newBook()).isEqualTo(new SearchResponse.NewBookInfo(13500, YES24_URL));
        assertThat(r.metadata().identification()).isEqualTo(new SearchResponse.IdentificationInfo(Source.YES24, Yes24Status.FOUND));
        assertStatus(r, SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SUCCESS, null);
        assertStatus(r, SearchSection.USED_BOOK, SearchSectionStatus.SUCCESS, null);
        assertStatus(r, SearchSection.NEW_BOOK, SearchSectionStatus.SUCCESS, null);
        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SUCCESS, null);
        assertThat(r.subscription().millie().available()).isTrue();
        assertThat(r.publicLibraries()).hasSize(1);
        assertThat(r.metadata().publicLibraryTotal()).isEqualTo(20);
        assertThat(r.metadata().publicLibraryHasMore()).isTrue();
        assertThat(r.metadata().publicLibraryNextOffset()).isEqualTo(5);
        assertThat(r.metadata().failures()).isEmpty();
    }

    // (b) 정상 ISBN, YES24 타임아웃, 힌트 있음, 위치 없음
    @Test
    void exampleB_yes24TimeoutWithHintWithoutLocation() {
        when(identificationService.identify(ISBN, HINT))
                .thenReturn(new IdentificationResult(Source.HINT, Yes24Status.TIMEOUT, true, HINT_BOOK, null, null));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, HINT));

        assertThat(r.book()).isEqualTo(new SearchResponse.BookInfo(
                "힌트 제목", "힌트 저자", ISBN, "힌트출판", "https://k.example/1.jpg"));
        assertThat(r.newBook()).isNull();
        assertThat(r.metadata().identification()).isEqualTo(new SearchResponse.IdentificationInfo(Source.HINT, Yes24Status.TIMEOUT));
        assertStatus(r, SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED, SearchSectionReason.NO_LOCATION);
        assertStatus(r, SearchSection.USED_BOOK, SearchSectionStatus.SUCCESS, null);
        assertStatus(r, SearchSection.NEW_BOOK, SearchSectionStatus.FAILED, SearchSectionReason.TIMEOUT);
        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SUCCESS, null);
        assertThat(r.metadata().failures()).containsExactly(
                new SearchResponse.FailureDetail(SearchSection.NEW_BOOK, "타임아웃"));
        // 힌트 기반 밀리 조회
        verify(millieBookService).findAvailability(HINT_BOOK);
        verify(publicLibraryAvailabilityService, never()).fetch(anyString(), anyDouble(), anyDouble(), anyInt());
    }

    // (c) 비표준 코드, 힌트 없음, 위치 있음 — 위치가 있어도 NON_STANDARD_ISBN이 NO_LOCATION보다 앞선다
    @Test
    void exampleC_nonStandardWithoutHintWithLocation() {
        when(identificationService.identifyWithoutLookup(CODE, null)).thenReturn(
                new IdentificationResult(Source.NONE, Yes24Status.SKIPPED, false,
                        new IdentifiedBook(null, null, CODE, null, null), null, null));

        SearchResponse r = assertConsistent(searchService.search(CODE, LAT, LON, null));

        assertThat(r.book()).isEqualTo(new SearchResponse.BookInfo(null, null, CODE, null, null));
        assertThat(r.newBook()).isNull();
        assertThat(r.usedBook()).isNull();
        assertThat(r.publicLibraries()).isEmpty();
        assertThat(r.metadata().identification()).isEqualTo(new SearchResponse.IdentificationInfo(Source.NONE, Yes24Status.SKIPPED));
        assertStatus(r, SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED, SearchSectionReason.NON_STANDARD_ISBN);
        assertStatus(r, SearchSection.USED_BOOK, SearchSectionStatus.SKIPPED, SearchSectionReason.NON_STANDARD_ISBN);
        assertStatus(r, SearchSection.NEW_BOOK, SearchSectionStatus.SKIPPED, SearchSectionReason.NON_STANDARD_ISBN);
        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SKIPPED, SearchSectionReason.NO_METADATA);
        assertThat(r.metadata().failures()).isEmpty();
        // F1: ISBN 기반 호출을 하지 않는다 (거짓 "미보유" 방지)
        verify(identificationService, never()).identify(anyString(), any());
        verifyNoInteractions(publicLibraryAvailabilityService, aladinBookService, millieBookService);
    }

    // 비표준 코드라도 힌트가 있으면 밀리는 조회한다 (source=HINT로 '검증되지 않은 힌트 기반'임이 남는다)
    @Test
    void nonStandardWithHintStillQueriesMillie() {
        IdentifiedBook hintForCode = new IdentifiedBook("힌트 제목", "힌트 저자", CODE, "힌트출판", "https://k.example/1.jpg");
        when(identificationService.identifyWithoutLookup(CODE, HINT)).thenReturn(
                new IdentificationResult(Source.HINT, Yes24Status.SKIPPED, false, hintForCode, null, null));
        when(millieBookService.findAvailability(hintForCode)).thenReturn(millieAvailable());

        SearchResponse r = assertConsistent(searchService.search(CODE, null, null, HINT));

        assertStatus(r, SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED, SearchSectionReason.NON_STANDARD_ISBN);
        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SUCCESS, null);
        assertThat(r.subscription().millie().available()).isTrue();
        assertThat(r.metadata().identification().source()).isEqualTo(Source.HINT);
        verifyNoInteractions(aladinBookService, publicLibraryAvailabilityService);
    }

    // ===== NEW_BOOK 매핑표 =====

    static Stream<Arguments> newBookRows() {
        return Stream.of(
                Arguments.of("FOUND 가격 있음", found(13500, YES24_URL),
                        SearchSectionStatus.SUCCESS, null, new SearchResponse.NewBookInfo(13500, YES24_URL), null),
                Arguments.of("FOUND 가격 없음", found(null, YES24_URL),
                        SearchSectionStatus.SUCCESS, SearchSectionReason.PRICE_UNAVAILABLE, null, null),
                // 구현 선택: 가격은 있는데 YES24 링크가 없으면(관측 0/88) 구매 링크를 만들 수 없어 가격 미제공으로 본다
                Arguments.of("FOUND 링크 없음", found(13500, null),
                        SearchSectionStatus.SUCCESS, SearchSectionReason.PRICE_UNAVAILABLE, null, null),
                Arguments.of("NOT_FOUND(GOODS_002)", miss(Yes24Status.NOT_FOUND, true),
                        SearchSectionStatus.SUCCESS, SearchSectionReason.NOT_FOUND, null, null),
                Arguments.of("TIMEOUT", miss(Yes24Status.TIMEOUT, true),
                        SearchSectionStatus.FAILED, SearchSectionReason.TIMEOUT, null, "타임아웃"),
                Arguments.of("FAILED", miss(Yes24Status.FAILED, true),
                        SearchSectionStatus.FAILED, SearchSectionReason.UNAVAILABLE, null, "YES24 오류"),
                Arguments.of("우리 쪽 선제한", miss(Yes24Status.RATE_LIMITED, false),
                        SearchSectionStatus.SKIPPED, SearchSectionReason.RATE_LIMITED, null, null),
                Arguments.of("YES24 RATE_001", miss(Yes24Status.RATE_LIMITED, true),
                        SearchSectionStatus.FAILED, SearchSectionReason.RATE_LIMITED, null, "YES24 호출 한도(초당)"),
                Arguments.of("일일 중단 중", miss(Yes24Status.DAILY_LIMIT, false),
                        SearchSectionStatus.SKIPPED, SearchSectionReason.DAILY_LIMIT, null, null),
                Arguments.of("YES24 RATE_002", miss(Yes24Status.DAILY_LIMIT, true),
                        SearchSectionStatus.FAILED, SearchSectionReason.DAILY_LIMIT, null, "YES24 호출 한도(일일)")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("newBookRows")
    void newBookMapping(String label, IdentificationResult identification, SearchSectionStatus status,
                        SearchSectionReason reason, SearchResponse.NewBookInfo newBook, String failureReason) {
        when(identificationService.identify(ISBN, null)).thenReturn(identification);

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.NEW_BOOK, status, reason);
        assertThat(r.newBook()).isEqualTo(newBook);
        if (failureReason != null) {
            assertThat(r.metadata().failures()).contains(new SearchResponse.FailureDetail(SearchSection.NEW_BOOK, failureReason));
        }
    }

    // ===== SUBSCRIPTION =====

    @Test
    void subscriptionSkippedWithNoMetadataWhenSourceNone() {
        when(identificationService.identify(ISBN, null)).thenReturn(miss(Yes24Status.TIMEOUT, true));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SKIPPED, SearchSectionReason.NO_METADATA);
        assertThat(r.subscription().millie().available()).isFalse();
        assertThat(r.book()).isEqualTo(new SearchResponse.BookInfo(null, null, ISBN, null, null));
        verify(millieBookService, never()).findAvailability(any());
    }

    @Test
    void subscriptionNormalEmptyResultIsSuccess() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.SUCCESS, null);
        assertThat(r.subscription().millie().available()).isFalse();
    }

    @Test
    void subscriptionClientFailureIsFailedUnavailable() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(millieBookService.findAvailability(any())).thenThrow(new MillieClientException("밀리 HTTP 500"));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.FAILED, SearchSectionReason.UNAVAILABLE);
        assertThat(r.metadata().failures()).containsExactly(
                new SearchResponse.FailureDetail(SearchSection.SUBSCRIPTION, "밀리 HTTP 500"));
    }

    @Test
    void subscriptionClientTimeoutIsFailedTimeout() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(millieBookService.findAvailability(any())).thenThrow(
                new MillieClientException("밀리 호출 실패", new RuntimeException(new SocketTimeoutException("Read timed out"))));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.FAILED, SearchSectionReason.TIMEOUT);
    }

    @Test
    void subscriptionSlowerThanDeadlineIsFailedTimeout() {
        ReflectionTestUtils.setField(searchService, "totalDeadlineMs", 200L);
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(millieBookService.findAvailability(any())).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return millieAvailable();
        });

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.SUBSCRIPTION, SearchSectionStatus.FAILED, SearchSectionReason.TIMEOUT);
        assertThat(r.subscription().millie().available()).isFalse();
        assertThat(r.metadata().failures()).containsExactly(
                new SearchResponse.FailureDetail(SearchSection.SUBSCRIPTION, "타임아웃"));
    }

    // 데드라인 뒤에 늦게 실패한 작업이 이미 만든 응답의 failures를 바꾸지 못한다(이전 구조의 경쟁 조건)
    @Test
    void lateFailureAfterDeadlineDoesNotChangeResponse() throws Exception {
        ReflectionTestUtils.setField(searchService, "totalDeadlineMs", 100L);
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(millieBookService.findAvailability(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            throw new MillieClientException("늦은 실패");
        });

        SearchResponse r = searchService.search(ISBN, null, null, null);
        Thread.sleep(500);   // 늦은 실패가 끝난 뒤에도

        assertConsistent(r);
        assertThat(r.metadata().failures()).containsExactly(
                new SearchResponse.FailureDetail(SearchSection.SUBSCRIPTION, "타임아웃"));
    }

    // ===== PUBLIC_LIBRARY / USED_BOOK =====

    @Test
    void publicLibraryFailureIsFailedUnavailable() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(publicLibraryAvailabilityService.fetch(ISBN, LAT, LON, 0)).thenThrow(new IllegalStateException("정보나루 오류"));

        SearchResponse r = assertConsistent(searchService.search(ISBN, LAT, LON, null));

        assertStatus(r, SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.FAILED, SearchSectionReason.UNAVAILABLE);
        assertThat(r.publicLibraries()).isEmpty();
        assertThat(r.metadata().publicLibraryTotal()).isNull();
    }

    @Test
    void usedBookFailureIsFailedUnavailable() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));
        when(aladinBookService.getUsedBooks(ISBN)).thenThrow(new RuntimeException("알라딘 오류"));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.USED_BOOK, SearchSectionStatus.FAILED, SearchSectionReason.UNAVAILABLE);
        assertThat(r.usedBook()).isNull();
        assertThat(r.metadata().failures()).containsExactly(
                new SearchResponse.FailureDetail(SearchSection.USED_BOOK, "알라딘 오류"));
    }

    @Test
    void usedBookSuccessCarriesPrices() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertStatus(r, SearchSection.USED_BOOK, SearchSectionStatus.SUCCESS, null);
        assertThat(r.usedBook().userUsedPrice()).isEqualTo(6500);
    }

    // executor 거절(풀 포화)도 FAILED — reason은 UNAVAILABLE, 원인은 failures 문구로 구분 (결정 E5)
    @Test
    void executorRejectionIsFailedUnavailableWithPoolSaturationReason() {
        searchExecutor.shutdownNow();   // 이후 제출은 모두 RejectedExecutionException
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));

        SearchResponse r = assertConsistent(searchService.search(ISBN, LAT, LON, null));

        for (SearchSection section : List.of(SearchSection.PUBLIC_LIBRARY, SearchSection.USED_BOOK, SearchSection.SUBSCRIPTION)) {
            assertStatus(r, section, SearchSectionStatus.FAILED, SearchSectionReason.UNAVAILABLE);
            assertThat(r.metadata().failures()).contains(new SearchResponse.FailureDetail(section, "검색 풀 포화"));
        }
        // 식별은 풀을 거치지 않으므로 새 책은 그대로
        assertStatus(r, SearchSection.NEW_BOOK, SearchSectionStatus.SUCCESS, null);
    }

    @Test
    void withoutLocationHasNullPagingMetadata() {
        when(identificationService.identify(ISBN, null)).thenReturn(found(13500, YES24_URL));

        SearchResponse r = assertConsistent(searchService.search(ISBN, null, null, null));

        assertThat(r.metadata().publicLibraryTotal()).isNull();
        assertThat(r.metadata().publicLibraryHasMore()).isFalse();
        assertThat(r.metadata().publicLibraryNextOffset()).isNull();
    }

    // ===== helpers =====

    private static IdentificationResult found(Integer price, String url) {
        return new IdentificationResult(Source.YES24, Yes24Status.FOUND, true, YES24_BOOK, price, url);
    }

    private static IdentificationResult miss(Yes24Status status, boolean sent) {
        return new IdentificationResult(Source.NONE, status, sent, new IdentifiedBook(null, null, ISBN, null, null), null, null);
    }

    private static MillieAvailability millieAvailable() {
        return new MillieAvailability(true, "ABC", "https://www.millie.co.kr/v3/book/ABC", MillieAvailability.Format.EBOOK);
    }

    private static PublicLibraryAvailabilityPage libraryPage() {
        return new PublicLibraryAvailabilityPage(
                List.of(new PublicLibraryInfo("종로도서관", true, false, "서울 종로구", 37.57, 126.98, 0.5, "https://lib.example")),
                0, 20, 5, true, 0);
    }

    private static void assertStatus(SearchResponse r, SearchSection section, SearchSectionStatus status, SearchSectionReason reason) {
        assertThat(r.metadata().sectionStatuses())
                .filteredOn(s -> s.section() == section)
                .singleElement()
                .isEqualTo(new SectionStatusDetail(section, status, reason));
    }
}
