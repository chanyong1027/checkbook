package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibraryResponse;
import com.checkbook.elibrary.repository.ELibraryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ELibraryServiceTest {

    @Mock
    private ELibraryRepository eLibraryRepository;

    @InjectMocks
    private ELibraryService eLibraryService;

    @Test
    void readLibrariesNoFilterReturnsAll() {
        ELibrary library = ELibrary.builder()
                .name("테스트도서관")
                .baseUrl("https://test.dkyobobook.co.kr")
                .vendorType(VendorType.KYOBO)
                .status(ELibraryStatus.ACTIVE)
                .region("11")
                .loginRequired(false)
                .build();

        when(eLibraryRepository.findAllByFilterWithoutKeyword(
                eq(ELibraryStatus.ACTIVE),
                isNull(),
                isNull())
        ).thenReturn(List.of(library));

        List<ELibraryResponse> result = eLibraryService.readLibraries(null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("테스트도서관");
        assertThat(result.get(0).vendorType()).isEqualTo("KYOBO");
    }

    @Test
    void readLibrariesKeywordUsesKeywordFilter() {
        ELibrary library = ELibrary.builder()
                .name("자바도서관")
                .baseUrl("https://test.dkyobobook.co.kr")
                .vendorType(VendorType.KYOBO)
                .status(ELibraryStatus.ACTIVE)
                .region("11")
                .loginRequired(false)
                .build();

        when(eLibraryRepository.findAllByFilterWithKeyword(
                eq(ELibraryStatus.ACTIVE),
                isNull(),
                isNull(),
                eq("자바"))
        ).thenReturn(List.of(library));

        List<ELibraryResponse> result = eLibraryService.readLibraries(null, null, "자바");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("자바도서관");
    }

    @Test
    void readLibrariesInvalidVendorTypeThrowsException() {
        assertThatThrownBy(() -> eLibraryService.readLibraries(null, "INVALID", null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VENDOR_TYPE);
    }

    @Test
    void readLibrariesInvalidRegionThrowsException() {
        assertThatThrownBy(() -> eLibraryService.readLibraries("abc", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REGION_CODE);
    }
}
