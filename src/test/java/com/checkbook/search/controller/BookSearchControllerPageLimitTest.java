package com.checkbook.search.controller;

import com.checkbook.search.dto.BookCandidateResponse;
import com.checkbook.search.service.BookSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * page 상한(@Max)은 클래스의 @Validated가 만드는 프록시가 있어야 적용된다. BookSearchControllerTest의
 * standaloneSetup에는 그 프록시가 없어 상한을 시험할 수 없으므로 @WebMvcTest로 따로 둔다.
 */
@WebMvcTest(BookSearchController.class)
class BookSearchControllerPageLimitTest {

    @Autowired MockMvc mvc;
    @MockitoBean BookSearchService bookSearchService;

    // 카카오 책 검색은 page 1~50만 받는다(BookSearchService.MAX_PAGE). 상한은 통과, 상한+1은 입구에서 400
    @Test
    void 상한_쪽까지는_서비스로_넘긴다() throws Exception {
        int maxPage = BookSearchService.MAX_PAGE;
        when(bookSearchService.searchCandidates("혼모노", maxPage, 10))
                .thenReturn(new BookCandidateResponse(
                        List.of(),
                        new BookCandidateResponse.Pagination(maxPage, 10, 0, true)
                ));

        mvc.perform(get("/api/books/search")
                        .param("q", "혼모노")
                        .param("page", String.valueOf(maxPage)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(maxPage));
    }

    @Test
    void 상한을_넘는_쪽은_서비스를_부르지_않고_400() throws Exception {
        mvc.perform(get("/api/books/search")
                        .param("q", "혼모노")
                        .param("page", String.valueOf(BookSearchService.MAX_PAGE + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGING_PARAMETER"));

        verifyNoInteractions(bookSearchService);
    }

    // page·size 범위 위반은 '검색어를 입력해주세요'가 아니라 페이지 범위 오류로 알린다
    @ParameterizedTest
    @CsvSource({"page,0", "size,0", "size,51"})
    void 쪽_번호와_크기가_범위를_벗어나면_페이지_범위_오류(String name, String value) throws Exception {
        mvc.perform(get("/api/books/search")
                        .param("q", "혼모노")
                        .param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGING_PARAMETER"))
                .andExpect(jsonPath("$.message").value("요청한 페이지 범위가 올바르지 않아요."));

        verifyNoInteractions(bookSearchService);
    }
}
