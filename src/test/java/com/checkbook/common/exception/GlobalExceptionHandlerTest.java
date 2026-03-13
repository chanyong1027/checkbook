package com.checkbook.common.exception;

import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Validated
    @RestController
    static class TestController {

        @GetMapping("/test/biz")
        void biz() {
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }

        @GetMapping("/test/search")
        void search(@RequestParam @NotBlank String q) {
        }

        @GetMapping("/test/err")
        void err() {
            throw new RuntimeException("oops");
        }
    }

    @Test
    void businessExceptionReturns404() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void missingParamQReturns400InvalidSearchKeyword() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_KEYWORD"));
    }

    @Test
    void genericExceptionReturns500() throws Exception {
        mockMvc.perform(get("/test/err"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }
}
