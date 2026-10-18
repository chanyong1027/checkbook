package com.checkbook.search.controller;

import com.checkbook.common.exception.GlobalExceptionHandler;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalProvider;
import com.checkbook.search.dto.BookCandidateResponse;
import com.checkbook.search.service.BookSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BookSearchControllerTest {

    @Mock
    private BookSearchService bookSearchService;

    @InjectMocks
    private BookSearchController bookSearchController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(bookSearchController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 카카오_예산_소진이면_503과_안내_문구() throws Exception {
        when(bookSearchService.searchCandidates("혼모노", 1, 10))
                .thenThrow(new DailyBudgetExceededException(ExternalProvider.KAKAO, Duration.ofSeconds(60)));

        mockMvc.perform(get("/api/books/search").param("q", "혼모노"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("PROVIDER_DAILY_LIMIT"));
    }

    @Test
    void searchBooksReturnsItemsAndPagination() throws Exception {
        when(bookSearchService.searchCandidates("혼모노", 1, 10))
                .thenReturn(new BookCandidateResponse(
                        List.of(new BookCandidateResponse.BookCandidate(
                                "혼모노", "성해나", "창비",
                                "9788936439743", "https://cover.jpg", "2021-09-17")),
                        new BookCandidateResponse.Pagination(1, 10, 1, true)
                ));

        mockMvc.perform(get("/api/books/search").param("q", "혼모노"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].isbn13").value("9788936439743"))
                .andExpect(jsonPath("$.items[0].title").value("혼모노"))
                .andExpect(jsonPath("$.items[0].author").value("성해나"))
                .andExpect(jsonPath("$.items[0].publisher").value("창비"))
                .andExpect(jsonPath("$.items[0].coverUrl").value("https://cover.jpg"))
                .andExpect(jsonPath("$.items[0].publishedAt").value("2021-09-17"))
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.totalCount").value(1))
                .andExpect(jsonPath("$.pagination.isEnd").value(true));
    }

    @Test
    void searchBooksPageAndSizeParamsPassedToService() throws Exception {
        when(bookSearchService.searchCandidates("자바", 2, 20))
                .thenReturn(new BookCandidateResponse(
                        List.of(),
                        new BookCandidateResponse.Pagination(2, 20, 0, true)
                ));

        mockMvc.perform(get("/api/books/search")
                        .param("q", "자바")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(2))
                .andExpect(jsonPath("$.pagination.size").value(20));
    }

    @Test
    void searchBooksWithoutQReturns400() throws Exception {
        mockMvc.perform(get("/api/books/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchBooksWithBlankQReturns400() throws Exception {
        mockMvc.perform(get("/api/books/search").param("q", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchBooksDefaultPageAndSizeUsedWhenNotProvided() throws Exception {
        when(bookSearchService.searchCandidates("혼모노", 1, 10))
                .thenReturn(new BookCandidateResponse(
                        List.of(),
                        new BookCandidateResponse.Pagination(1, 10, 0, true)
                ));

        mockMvc.perform(get("/api/books/search").param("q", "혼모노"))
                .andExpect(status().isOk());
    }
}
