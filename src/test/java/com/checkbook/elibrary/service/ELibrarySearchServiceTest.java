package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.elibrary.client.ELibClient;
import com.checkbook.elibrary.client.ELibClientResolver;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.dto.ELibrarySearchStatus;
import com.checkbook.elibrary.repository.ELibraryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ELibrarySearchServiceTest {

    @Mock
    private ELibraryRepository eLibraryRepository;

    @Mock
    private ELibClientResolver clientResolver;

    @Mock
    private ELibClient mockClient;

    private ExecutorService eLibraryExecutor;
    private ELibrarySearchService service;

    @BeforeEach
    void setUp() {
        eLibraryExecutor = Executors.newFixedThreadPool(5);
        service = new ELibrarySearchService(eLibraryRepository, clientResolver, eLibraryExecutor);
    }

    @AfterEach
    void tearDown() {
        eLibraryExecutor.shutdownNow();
    }

    @Test
    void searchMissingLibraryIdsThrowsException() {
        assertThatThrownBy(() -> service.search("자바", "", null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_IDS_REQUIRED);
    }

    @Test
    void searchTooManyIdsThrowsException() {
        String ids = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21";

        assertThatThrownBy(() -> service.search("자바", ids, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_IDS_LIMIT_EXCEEDED);
    }

    @Test
    void searchDuplicateIdsWithinUniqueLimitDoesNotThrow() {
        ELibrary library = activeLibrary(1L);

        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString())).thenReturn(List.of());

        ELibrarySearchResponse response = service.search(
                "자바",
                "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1",
                null
        );

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.SUCCESS);
    }

    @Test
    void searchStaleIdRecordedAsFailure() {
        when(eLibraryRepository.findAllById(List.of(999L))).thenReturn(List.of());

        ELibrarySearchResponse response = service.search("자바", "999", null);

        assertThat(response.results()).isEmpty();
        assertThat(response.metadata().failures()).hasSize(1);
        assertThat(response.metadata().failures().get(0).libraryId()).isEqualTo(999L);
    }

    @Test
    void searchActiveLibraryReturnsResult() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString()))
                .thenReturn(List.of(new ELibrarySearchResponse.ELibraryBook(
                        "자바의 정석", "남궁성", "도우출판", null, true, null
                )));

        ELibrarySearchResponse response = service.search("자바", "3", null);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.SUCCESS);
        assertThat(response.results().get(0).books()).hasSize(1);
    }

    @Test
    void searchInactiveAndLoginRequiredRecordedAsFailures() {
        ELibrary inactive = activeLibrary(3L);
        ReflectionTestUtils.setField(inactive, "status", ELibraryStatus.INACTIVE);
        ELibrary loginRequired = activeLibrary(5L);
        ReflectionTestUtils.setField(loginRequired, "loginRequired", true);

        when(eLibraryRepository.findAllById(List.of(3L, 5L))).thenReturn(List.of(inactive, loginRequired));

        ELibrarySearchResponse response = service.search("자바", "3,5", null);

        assertThat(response.results()).isEmpty();
        assertThat(response.metadata().failures()).hasSize(2);
        assertThat(response.metadata().failures())
                .extracting(ELibrarySearchResponse.ELibraryFailureDetail::libraryId)
                .containsExactly(3L, 5L);
    }

    @Test
    void searchFallbackRunsOnlyForIsbnQuery() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(library.getBaseUrl(), "9788936439743")).thenReturn(List.of());
        when(mockClient.search(library.getBaseUrl(), "혼자가 혼자에게"))
                .thenReturn(List.of(new ELibrarySearchResponse.ELibraryBook(
                        "혼자가 혼자에게", "성해나", "창비", null, true, null
                )));

        ELibrarySearchResponse response = service.search("9788936439743", "3", "혼자가 혼자에게");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).books()).hasSize(1);
        verify(mockClient).search(library.getBaseUrl(), "9788936439743");
        verify(mockClient).search(library.getBaseUrl(), "혼자가 혼자에게");
    }

    @Test
    void searchFallbackDoesNotRunForKeywordQuery() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(library.getBaseUrl(), "혼모노")).thenReturn(List.of());

        ELibrarySearchResponse response = service.search("혼모노", "3", "혼자가 혼자에게");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).books()).isEmpty();
        verify(mockClient).search(library.getBaseUrl(), "혼모노");
        verify(mockClient, never()).search(library.getBaseUrl(), "혼자가 혼자에게");
    }

    @Test
    void searchClientThrowsMarksLibraryAsFailed() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(library.getBaseUrl(), "자바")).thenThrow(new RuntimeException("parse error"));

        ELibrarySearchResponse response = service.search("자바", "3", null);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.FAILED);
        assertThat(response.results().get(0).books()).isEmpty();
    }

    private ELibrary activeLibrary(Long id) {
        ELibrary library = ELibrary.builder()
                .name("테스트도서관")
                .baseUrl("https://test.dkyobobook.co.kr")
                .vendorType(VendorType.KYOBO)
                .status(ELibraryStatus.ACTIVE)
                .loginRequired(false)
                .build();
        ReflectionTestUtils.setField(library, "id", id);
        return library;
    }
}
