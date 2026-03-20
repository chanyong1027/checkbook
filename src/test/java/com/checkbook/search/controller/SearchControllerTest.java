package com.checkbook.search.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.exception.GlobalExceptionHandler;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private SearchController searchController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
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
}
