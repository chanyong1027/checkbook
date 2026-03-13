package com.checkbook.elibrary.controller;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.exception.GlobalExceptionHandler;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.service.ELibrarySearchService;
import com.checkbook.elibrary.service.ELibraryService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ELibrarySearchControllerTest {

    @Mock
    private ELibraryService eLibraryService;

    @Mock
    private ELibrarySearchService eLibrarySearchService;

    @InjectMocks
    private ELibraryController eLibraryController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(eLibraryController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchReturns200() throws Exception {
        ELibrarySearchResponse response = new ELibrarySearchResponse(
                List.of(),
                new ELibrarySearchResponse.ELibrarySearchMetadata(123L, LocalDateTime.now(), List.of())
        );
        when(eLibrarySearchService.search("자바", "3", null)).thenReturn(response);

        mockMvc.perform(get("/api/elibraries/search")
                        .param("query", "자바")
                        .param("libraryIds", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.totalElapsedMs").value(123));
    }

    @Test
    void searchWithoutLibraryIdsReturns400() throws Exception {
        mockMvc.perform(get("/api/elibraries/search")
                        .param("query", "자바"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIBRARY_IDS_REQUIRED"));
    }

    @Test
    void searchInvalidLibraryIdReturns400() throws Exception {
        when(eLibrarySearchService.search(eq("자바"), eq("abc"), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_LIBRARY_ID));

        mockMvc.perform(get("/api/elibraries/search")
                        .param("query", "자바")
                        .param("libraryIds", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIBRARY_ID"));
    }
}
