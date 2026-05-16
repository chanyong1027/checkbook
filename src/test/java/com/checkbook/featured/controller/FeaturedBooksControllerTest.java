package com.checkbook.featured.controller;

import com.checkbook.featured.dto.FeaturedBooksResponse;
import com.checkbook.featured.service.FeaturedBooksService;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeaturedBooksController.class)
class FeaturedBooksControllerTest {

    @Autowired MockMvc mvc;
    @MockBean FeaturedBooksService service;

    @Test
    void getFeatured_bestseller_returnsResponse() throws Exception {
        when(service.getSection(FeaturedSectionType.BESTSELLER)).thenReturn(
                new FeaturedBooksResponse(
                        FeaturedSectionType.BESTSELLER,
                        FeaturedSource.ALADIN,
                        List.of(new FeaturedBooksResponse.Item(
                                "소년이 온다", "한강", "창비",
                                "9788936434120", "https://cover/1.jpg", "2014-05-19")),
                        Instant.parse("2026-05-13T19:00:00Z"),
                        false
                )
        );

        mvc.perform(get("/api/featured").param("type", "bestseller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BESTSELLER"))
                .andExpect(jsonPath("$.source").value("ALADIN"))
                .andExpect(jsonPath("$.items[0].isbn13").value("9788936434120"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void getFeatured_unknownType_400() throws Exception {
        mvc.perform(get("/api/featured").param("type", "garbage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFeatured_typeMissing_400() throws Exception {
        mvc.perform(get("/api/featured"))
                .andExpect(status().isBadRequest());
    }
}
