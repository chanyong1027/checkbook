package com.checkbook.common.exception;

import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import com.checkbook.common.ratelimit.ExternalProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
            throw new BusinessException(ErrorCode.LIBRARY_NOT_FOUND);
        }

        @GetMapping("/test/search")
        void search(@RequestParam @NotBlank String q) {
        }

        @GetMapping("/test/err")
        void err() {
            throw new RuntimeException("oops");
        }

        @GetMapping("/test/budget")
        void budget() {
            throw new DailyBudgetExceededException(ExternalProvider.KAKAO, Duration.ofSeconds(120));
        }
    }

    @Test
    void businessExceptionReturns404() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LIBRARY_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void missingParamQReturns400InvalidSearchKeyword() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_KEYWORD"));
    }

    @Test
    void 공급자_예산_소진은_503과_Retry_After() throws Exception {
        mockMvc.perform(get("/test/budget"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.code").value("PROVIDER_DAILY_LIMIT"));
    }

    @Test
    void genericExceptionReturns500() throws Exception {
        mockMvc.perform(get("/test/err"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }
}
