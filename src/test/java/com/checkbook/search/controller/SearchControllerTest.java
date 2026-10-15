package com.checkbook.search.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.exception.GlobalExceptionHandler;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.OffStoreResponse;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionReason;
import com.checkbook.search.dto.SearchSectionStatus;
import com.checkbook.search.service.AladinBookService;
import com.checkbook.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    private static final String NON_STANDARD_ISBN_MESSAGE = "표준 ISBN이 아니라 조회할 수 없습니다.";

    @Mock
    private SearchService searchService;

    @Mock
    private AladinBookService aladinBookService;

    @Mock
    private PublicLibraryAvailabilityService publicLibraryAvailabilityService;

    private SearchController searchController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        searchController = new SearchController(
                searchService, aladinBookService, publicLibraryAvailabilityService);

        // 운영에서는 spring-boot-starter-validation의 MethodValidationPostProcessor가
        // @Validated 컨트롤러 빈을 AOP 프록시로 감싸 @RequestParam 제약(@NotBlank/@Pattern/@Min)을
        // ConstraintViolationException으로 던진다. standaloneSetup(controller)은 컨트롤러를
        // 빈 컨테이너를 거치지 않고 그대로 등록하므로 그 프록시가 만들어지지 않는다.
        // 동일한 검증 동작을 재현하기 위해 여기서 직접 프록시를 씌운다.
        MethodValidationPostProcessor methodValidationPostProcessor = new MethodValidationPostProcessor();
        methodValidationPostProcessor.afterPropertiesSet();
        Object validatedController = methodValidationPostProcessor
                .postProcessAfterInitialization(searchController, "searchController");

        mockMvc = MockMvcBuilders.standaloneSetup(validatedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchWithIsbnReturns200WithReasonAndIdentification() throws Exception {
        when(searchService.search(eq("9788994492032"), isNull(), isNull(), isNull()))
                .thenReturn(new SearchResponse(
                        new SearchResponse.BookInfo("자바의 정석", "남궁성", "9788994492032", "도우출판", null),
                        List.of(),
                        new SearchResponse.UsedBookInfo("https://www.aladin.co.kr/shop/UsedShop/wuseditemall.aspx?ISBN=9788994492032"),
                        new SearchResponse.NewBookInfo(32000, "https://www.yes24.com/product/goods/1"),
                        new SearchResponse.SubscriptionInfo(MillieAvailability.unavailable()),
                        new SearchResponse.SearchMetadata(
                                LocalDateTime.now(),
                                List.of(new SearchResponse.SectionStatusDetail(
                                                SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED, SearchSectionReason.NO_LOCATION),
                                        new SearchResponse.SectionStatusDetail(
                                                SearchSection.NEW_BOOK, SearchSectionStatus.SUCCESS, null)),
                                List.of(), null, false, null,
                                new SearchResponse.IdentificationInfo(
                                        IdentificationResult.Source.YES24, IdentificationResult.Yes24Status.FOUND))
                ));

        mockMvc.perform(get("/api/search").param("q", "9788994492032"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.title").value("자바의 정석"))
                .andExpect(jsonPath("$.publicLibraries").isArray())
                .andExpect(jsonPath("$.newBook.price").value(32000))
                .andExpect(jsonPath("$.usedBook.productUrl").value(
                        "https://www.aladin.co.kr/shop/UsedShop/wuseditemall.aspx?ISBN=9788994492032"))
                // PR-2: 중고 칸은 링크 하나만 — 옛 가격·채널 링크 필드 6개는 응답에서 빠진다
                .andExpect(jsonPath("$.usedBook.*", hasSize(1)))
                .andExpect(jsonPath("$.usedBook.userUsedPrice").doesNotExist())
                .andExpect(jsonPath("$.usedBook.spaceUsedUrl").doesNotExist())
                // 계약 2절: reason은 기계용 코드(nullable), identification{source, yes24}
                .andExpect(jsonPath("$.metadata.sectionStatuses[0].reason").value("NO_LOCATION"))
                .andExpect(jsonPath("$.metadata.sectionStatuses[1].reason").value(nullValue()))
                .andExpect(jsonPath("$.metadata.identification.source").value("YES24"))
                .andExpect(jsonPath("$.metadata.identification.yes24").value("FOUND"));
    }

    @Test
    void searchPassesHintParamsToService() throws Exception {
        when(searchService.search(any(), any(), any(), any())).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search")
                        .param("q", "9788994492032")
                        .param("title", "자바의 정석")
                        .param("author", "남궁성")
                        .param("publisher", "도우출판")
                        .param("cover", "https://k.example/1.jpg"))
                .andExpect(status().isOk());

        verify(searchService).search("9788994492032", null, null,
                new BookHint("자바의 정석", "남궁성", "도우출판", "https://k.example/1.jpg"));
    }

    @Test
    void searchWithoutHintParamsPassesNullHint() throws Exception {
        when(searchService.search(any(), any(), any(), any())).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search").param("q", "9788994492032"))
                .andExpect(status().isOk());

        verify(searchService).search("9788994492032", null, null, null);
    }

    // 힌트는 선택 기능이라 무효해도 400이 아니다 — 컨트롤러는 길이 제약 없이 넘기고 서비스가 판정한다
    @Test
    void searchWithOverlongHintIsNotRejected() throws Exception {
        when(searchService.search(any(), any(), any(), any())).thenReturn(emptyResponse());

        mockMvc.perform(get("/api/search")
                        .param("q", "9788994492032")
                        .param("title", "가".repeat(500)))
                .andExpect(status().isOk());
    }

    @Test
    void searchWithKeywordReturns400KeywordNotSupported() throws Exception {
        when(searchService.search(eq("자바"), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.KEYWORD_NOT_SUPPORTED));

        mockMvc.perform(get("/api/search").param("q", "자바"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("KEYWORD_NOT_SUPPORTED"));
    }

    @Test
    void searchWithoutQReturns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_KEYWORD"));
    }

    @Test
    void searchInvalidLatTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "자바")
                        .param("lat", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    void searchOnlyLatProvidedReturns400() throws Exception {
        when(searchService.search(any(), any(), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_LOCATION));

        mockMvc.perform(get("/api/search")
                        .param("q", "자바")
                        .param("lat", "37.5665"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    void offStoresWithAllParamsReturns200() throws Exception {
        when(aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780))
                .thenReturn(new OffStoreResponse(List.of(
                        new OffStoreResponse.StoreInfo(
                                "종로점", "서울 종로구", 0.5,
                                "https://link", 37.57, 126.99)
                )));

        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "9788936439743")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores").isArray())
                .andExpect(jsonPath("$.stores[0].storeName").value("종로점"))
                .andExpect(jsonPath("$.stores[0].distance").value(0.5))
                .andExpect(jsonPath("$.stores[0].link").value("https://link"))
                // 재고는 조회하지 않는다(robots 전면 차단) — 재고 필드가 없어야 화면이 있음·없음으로 오해하지 않는다
                .andExpect(jsonPath("$.stores[0].stockStatus").doesNotExist())
                .andExpect(jsonPath("$.stores[0].stockCount").doesNotExist())
                .andExpect(jsonPath("$.stores[0].checkedAt").doesNotExist());
    }

    @Test
    void offStoresWithoutIsbnReturns400() throws Exception {
        mockMvc.perform(get("/api/off-stores")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISBN_REQUIRED"));
    }

    @Test
    void offStoresWithNon13DigitIsbnReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "978893643412")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(aladinBookService);
    }

    @Test
    void offStoresWithoutLatReturns400() throws Exception {
        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "9788936439743"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    void offStoresApiFailureReturns500() throws Exception {
        when(aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780))
                .thenThrow(new IllegalStateException("매장 목록 조회 오류"));

        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "9788936439743")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void availabilityFirstPageReturns200() throws Exception {
        when(publicLibraryAvailabilityService.fetch("9788936439743", 37.5665, 126.9780, 0))
                .thenReturn(new PublicLibraryAvailabilityPage(
                        List.of(new PublicLibraryInfo(
                                "종로도서관", true, false, "서울 종로구",
                                37.57, 126.98, 0.5, "https://lib.example")),
                        0, 20, 5, true, 0));

        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "9788936439743")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.libraries[0].libraryName").value("종로도서관"))
                .andExpect(jsonPath("$.total").value(20))
                .andExpect(jsonPath("$.nextOffset").value(5))
                .andExpect(jsonPath("$.hasMoreLibraries").value(true))
                .andExpect(jsonPath("$.failedCount").value(0));
    }

    @Test
    void availabilityDefaultsOffsetToZero() throws Exception {
        when(publicLibraryAvailabilityService.fetch("9788936439743", 37.5665, 126.9780, 0))
                .thenReturn(new PublicLibraryAvailabilityPage(List.of(), 0, 0, null, false, 0));

        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "9788936439743")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk());
    }

    @Test
    void availabilityNegativeOffsetReturns400() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "9788936439743")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780")
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void availabilityBlankIsbnReturns400() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void availabilityNon13DigitIsbnReturns400() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "123")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest());
    }

    // F1: 숫자 13자리라도 978/979·체크섬이 맞지 않으면 도서관 조회가 무의미하다
    @Test
    void availabilityNonStandardIsbnReturns400WithoutFetching() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "2004566000649")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_STANDARD_ISBN"))
                .andExpect(jsonPath("$.message").value(NON_STANDARD_ISBN_MESSAGE));

        verify(publicLibraryAvailabilityService, never()).fetch(anyString(), anyDouble(), anyDouble(), anyInt());
    }

    // PR-2 F5: 매장 API도 같은 오류 코드를 쓰므로 문구가 '도서관'에 묶이면 안 된다
    @Test
    void offStoresNonStandardIsbnReturns400WithNeutralMessage() throws Exception {
        when(aladinBookService.getOffStoreList("2004566000649", 37.5665, 126.9780))
                .thenThrow(new BusinessException(ErrorCode.NON_STANDARD_ISBN));

        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "2004566000649")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_STANDARD_ISBN"))
                .andExpect(jsonPath("$.message").value(NON_STANDARD_ISBN_MESSAGE));
    }

    @Test
    void availabilityMissingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "9788936439743")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    private static SearchResponse emptyResponse() {
        return new SearchResponse(
                new SearchResponse.BookInfo(null, null, "9788994492032", null, null),
                List.of(), null, null,
                new SearchResponse.SubscriptionInfo(MillieAvailability.unavailable()),
                new SearchResponse.SearchMetadata(LocalDateTime.now(), List.of(), List.of(), null, false, null,
                        new SearchResponse.IdentificationInfo(
                                IdentificationResult.Source.NONE, IdentificationResult.Yes24Status.NOT_FOUND)));
    }
}
