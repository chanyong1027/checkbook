package com.checkbook.common.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스캐너가 두드리는 없는 경로·틀린 메서드가 500(+ ERROR 로그)이 아니라 404·405로 끝나는지.
 * 운영 access.log(10-18)에서 /api/.env, /api/config.json 등이 500으로 119건 남았다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class UnknownApiPathIT {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/.env", "/api/config.json", "/api/v1/config.json", "/api/"})
    void 없는_api_경로는_404_JSON이고_ERROR_로그를_남기지_않는다(String path, CapturedOutput output) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(output).doesNotContain("Unexpected error");
    }

    @Test
    void 지원하지_않는_메서드는_405_JSON과_Allow_헤더(CapturedOutput output) throws Exception {
        // RFC 9110 15.5.6: 405에는 Allow가 있어야 한다. @ExceptionHandler가 받으면 프레임워크 기본 처리가 붙이던 헤더가 빠진다
        mockMvc.perform(post("/api/featured").param("type", "BESTSELLER"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        assertThat(output).doesNotContain("Unexpected error");
    }
}
