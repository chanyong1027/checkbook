package com.checkbook.elibrary.controller;

import com.checkbook.elibrary.dto.ELibraryResponse;
import com.checkbook.elibrary.service.ELibraryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ELibraryListControllerTest {

    @Mock
    private ELibraryService eLibraryService;

    @InjectMocks
    private ELibraryController eLibraryController;

    @Test
    void readLibrariesReturns200() {
        when(eLibraryService.readLibraries(null, null, null))
                .thenReturn(List.of(
                        new ELibraryResponse(3L, "덕성여대 전자도서관", "KYOBO", "11")
                ));

        ResponseEntity<List<ELibraryResponse>> response = eLibraryController.readLibraries(null, null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).libraryId()).isEqualTo(3L);
        assertThat(response.getBody().get(0).vendorType()).isEqualTo("KYOBO");
        assertThat(response.getBody().get(0).region()).isEqualTo("11");
    }
}
