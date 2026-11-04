package com.checkbook.publiclibrary.controller;

import com.checkbook.common.exception.GlobalExceptionHandler;
import com.checkbook.publiclibrary.dto.BatchStopReason;
import com.checkbook.publiclibrary.dto.LibraryCheckFailure;
import com.checkbook.publiclibrary.dto.LibraryState;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchItem;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchResponse;
import com.checkbook.publiclibrary.link.PublicLibrarySearchLink;
import com.checkbook.publiclibrary.dto.PublicLibraryDirectoryResponse;
import com.checkbook.publiclibrary.service.PublicLibraryBatchService;
import com.checkbook.publiclibrary.service.PublicLibraryDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PublicLibraryControllerTest {

    private static final String ISBN = "9788936434120";
    private static final String BATCH = "/api/public-libraries/availability/batch";

    @Mock
    private PublicLibraryDirectoryService directoryService;
    @Mock
    private PublicLibraryBatchService batchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup은 @Validated 프록시를 만들지 않아 직접 씌운다(SearchControllerTest와 같은 이유)
        MethodValidationPostProcessor processor = new MethodValidationPostProcessor();
        processor.afterPropertiesSet();
        Object controller = processor.postProcessAfterInitialization(
                new PublicLibraryController(directoryService, batchService), "publicLibraryController");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static PublicLibraryBatchResponse sample() {
        return new PublicLibraryBatchResponse(List.of(
                new PublicLibraryBatchItem("111001", "용산도서관", "서울", 37.5, 127.0, 1.2,
                        null, LibraryState.UNKNOWN, LibraryCheckFailure.TIMEOUT, null)),
                5, BatchStopReason.BATCH_DEADLINE);
    }

    @Test
    void 좌표_주변_조회는_offset을_넘기고_상태와_중단_사유를_응답한다() throws Exception {
        when(batchService.nearby(ISBN, 37.5, 127.0, 5, null)).thenReturn(sample());

        mockMvc.perform(get(BATCH).param("isbn13", ISBN).param("mode", "NEARBY")
                        .param("lat", "37.5").param("lon", "127.0").param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].libCode").value("111001"))
                .andExpect(jsonPath("$.items[0].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[0].reason").value("TIMEOUT"))
                .andExpect(jsonPath("$.nextOffset").value(5))
                .andExpect(jsonPath("$.stopReason").value("BATCH_DEADLINE"));
    }

    @Test
    void 기준_기관_주변_조회는_offset_기본값_0() throws Exception {
        when(batchService.nearbyFromLibrary(ISBN, "111001", 0, null)).thenReturn(sample());

        mockMvc.perform(get(BATCH).param("isbn13", ISBN).param("mode", "NEARBY")
                        .param("referenceLibCode", "111001"))
                .andExpect(status().isOk());
    }

    @Test
    void 저장_모드는_쉼표_목록을_나눠_넘긴다() throws Exception {
        when(batchService.saved(ISBN, List.of("A1", "B2"), null)).thenReturn(sample());

        mockMvc.perform(get(BATCH).param("isbn13", ISBN).param("mode", "SAVED")
                        .param("libraryCodes", " A1, ,B2 "))
                .andExpect(status().isOk());
        verify(batchService).saved(ISBN, List.of("A1", "B2"), null);
    }

    @Test
    void 제목을_넘기면_사이트_검색_링크가_응답에_실린다() throws Exception {
        PublicLibraryBatchResponse withLink = new PublicLibraryBatchResponse(List.of(
                new PublicLibraryBatchItem("111001", "용산도서관", "서울", 37.5, 127.0, 1.2, null, LibraryState.AVAILABLE, null,
                        new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.RESULT, "POST", "https://lib.example/s",
                                Map.of("kwd", "흰")))),
                null, BatchStopReason.NONE);
        when(batchService.nearby(ISBN, 37.5, 127.0, 0, "흰")).thenReturn(withLink);

        mockMvc.perform(get(BATCH).param("isbn13", ISBN).param("mode", "NEARBY")
                        .param("lat", "37.5").param("lon", "127.0").param("title", "흰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].searchLink.kind").value("RESULT"))
                .andExpect(jsonPath("$.items[0].searchLink.method").value("POST"))
                .andExpect(jsonPath("$.items[0].searchLink.fields.kwd").value("흰"));
    }

    @Test
    void 제목이_200자를_넘으면_400() throws Exception {
        mockMvc.perform(get(BATCH).param("isbn13", ISBN).param("mode", "SAVED").param("libraryCodes", "A1")
                        .param("title", "가".repeat(201)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(batchService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 좌표와 기준 기관 둘 다 / 둘 다 없음 / 좌표 반쪽 / 저장 코드 섞임
            "mode=NEARBY&lat=37.5&lon=127.0&referenceLibCode=111001",
            "mode=NEARBY",
            "mode=NEARBY&lat=37.5",
            "mode=NEARBY&lat=37.5&lon=127.0&libraryCodes=A1",
            // 저장 모드에 좌표·기준·offset / 코드 없음 / 빈 코드
            "mode=SAVED&libraryCodes=A1&lat=37.5&lon=127.0",
            "mode=SAVED&libraryCodes=A1&referenceLibCode=111001",
            "mode=SAVED&libraryCodes=A1&offset=0",
            "mode=SAVED",
            "mode=SAVED&libraryCodes=,,",
            // 형식 오류
            "mode=NEARBY&lat=37.5&lon=127.0&offset=-1",
            "mode=WHATEVER&lat=37.5&lon=127.0",
            "mode=nearby&lat=37.5&lon=127.0"
    })
    void 조합이_틀리면_400이고_조회하지_않는다(String query) throws Exception {
        mockMvc.perform(get(BATCH + "?isbn13=" + ISBN + "&" + query))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(batchService);
    }

    @Test
    void 비표준_ISBN은_외부_조회_없이_400() throws Exception {
        mockMvc.perform(get(BATCH).param("isbn13", "1234567890123").param("mode", "NEARBY")
                        .param("lat", "37.5").param("lon", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NON_STANDARD_ISBN"));
        verifyNoInteractions(batchService);
    }

    @Test
    void 기관_검색은_기본_페이지와_크기를_쓴다() throws Exception {
        when(directoryService.search("용산", 0, 20)).thenReturn(new PublicLibraryDirectoryResponse(
                List.of(new PublicLibraryDirectoryResponse.Item("111001", "용산도서관", "서울", 37.5, 127.0)), true));

        mockMvc.perform(get("/api/public-libraries").param("keyword", "용산"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].libCode").value("111001"))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void 공백뿐인_검색어는_400() throws Exception {
        mockMvc.perform(get("/api/public-libraries").param("keyword", "   "))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(directoryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keyword=", "keyword=a&size=51", "keyword=a&size=0", "keyword=a&page=-1"})
    void 기관_검색_입력이_틀리면_400(String query) throws Exception {
        mockMvc.perform(get("/api/public-libraries?" + query))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(directoryService);
    }
}
