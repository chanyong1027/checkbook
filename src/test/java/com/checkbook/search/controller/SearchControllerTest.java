package com.checkbook.search.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.exception.GlobalExceptionHandler;
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

    private SearchController searchController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        searchController = new SearchController(searchService, aladinBookService);
        mockMvc = MockMvcBuilders.standaloneSetup(searchController)
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
                        new SearchResponse.SearchMetadata(LocalDateTime.now(), List.of(), List.of())
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
}
