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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

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
    void searchWithQReturns200() throws Exception {
        when(searchService.search(eq("자바"), isNull(), isNull()))
                .thenReturn(new SearchResponse(
                        new SearchResponse.BookInfo("자바의 정석", "남궁성", "9788994492032", "도우출판", null),
                        List.of(),
                        null,
                        new SearchResponse.NewBookInfo(32000, "https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=9788994492032"),
                        new SearchResponse.SubscriptionInfo(MillieAvailability.unavailable()),
                        new SearchResponse.SearchMetadata(
                                LocalDateTime.now(), List.of(), List.of(), null, false, null)
                ));

        mockMvc.perform(get("/api/search").param("q", "자바"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.title").value("자바의 정석"))
                .andExpect(jsonPath("$.publicLibraries").isArray())
                .andExpect(jsonPath("$.newBook.price").value(32000))
                .andExpect(jsonPath("$.metadata").exists());
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
        when(searchService.search(any(), any(), isNull()))
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
                .andExpect(jsonPath("$.stores[0].distance").value(0.5));
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
    void offStoresWithoutLatReturns400() throws Exception {
        mockMvc.perform(get("/api/off-stores")
                        .param("isbn13", "9788936439743"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    void offStoresApiFailureReturns500() throws Exception {
        when(aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780))
                .thenThrow(new IllegalStateException("알라딘 매장 재고 조회 오류"));

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

    @Test
    void availabilityMissingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/public-libraries/availability")
                        .param("isbn13", "9788936439743")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }
}
