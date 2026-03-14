package com.checkbook.publiclibrary.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class PublicLibraryDataLoaderTest {

    private final DatanaruClient datanaruClient = mock(DatanaruClient.class);
    private final PublicLibraryRepository repository = mock(PublicLibraryRepository.class);

    @Test
    void runSkipsWhenDataAlreadyExists() throws Exception {
        PublicLibraryDataLoader loader = new PublicLibraryDataLoader(datanaruClient, repository);
        when(repository.count()).thenReturn(1L);

        loader.run(new DefaultApplicationArguments(new String[0]));

        verify(datanaruClient, never()).libSrch(anyInt(), anyInt());
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void runStopsWhenPageIsSmallerThanPageSize() throws Exception {
        PublicLibraryDataLoader loader = new PublicLibraryDataLoader(datanaruClient, repository);
        when(repository.count()).thenReturn(0L);
        when(datanaruClient.libSrch(1, 100)).thenReturn(List.of(validResult("111111", 37.57, 126.98)));

        loader.run(new DefaultApplicationArguments(new String[0]));

        verify(datanaruClient, times(1)).libSrch(1, 100);
        verify(repository, times(1)).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void runFiltersOutLibrariesWithoutCoordinates() throws Exception {
        PublicLibraryDataLoader loader = new PublicLibraryDataLoader(datanaruClient, repository);
        when(repository.count()).thenReturn(0L);
        when(datanaruClient.libSrch(1, 100)).thenReturn(List.of(
                validResult("111111", 37.57, 126.98),
                new DatanaruLibSrchResult(
                        "222222",
                        "좌표없음도서관",
                        "서울 어딘가",
                        null,
                        126.98,
                        "서울특별시",
                        "https://invalid.example",
                        null,
                        null,
                        null,
                        null
                )
        ));

        loader.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<List<PublicLibrary>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getLibCode()).isEqualTo("111111");
    }

    private DatanaruLibSrchResult validResult(String libCode, double lat, double lon) {
        return new DatanaruLibSrchResult(
                libCode,
                "종로도서관",
                "서울 종로구",
                lat,
                lon,
                "서울특별시",
                "https://lib.example",
                "02-123-4567",
                null,
                "09:00~18:00",
                "매주 월요일"
        );
    }
}
