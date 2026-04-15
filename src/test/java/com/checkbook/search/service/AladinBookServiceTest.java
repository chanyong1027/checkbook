package com.checkbook.search.service;

import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.InputNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AladinBookServiceTest {

    @Mock
    private AladinClient aladinClient;

    @Mock
    private AladinStoreRepository aladinStoreRepository;

    @InjectMocks
    private AladinBookService aladinBookService;

    @Test
    void identifyKeywordDelegatesToSearchBook() {
        AladinSearchResult expected = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        when(aladinClient.searchBook("혼모노")).thenReturn(Optional.of(expected));

        InputNormalizer.NormalizedQuery query =
                new InputNormalizer.NormalizedQuery("혼모노", InputNormalizer.QueryType.KEYWORD);
        Optional<AladinSearchResult> result = aladinBookService.identify(query);

        assertThat(result).contains(expected);
        verify(aladinClient).searchBook("혼모노");
    }

    @Test
    void identifyIsbnDelegatesToLookupBook() {
        AladinSearchResult expected = new AladinSearchResult(
                "9788936439743", "혼자가 혼자에게", "성해나", "창비", null, 16800);
        when(aladinClient.lookupBook("9788936439743")).thenReturn(Optional.of(expected));

        InputNormalizer.NormalizedQuery query =
                new InputNormalizer.NormalizedQuery("9788936439743", InputNormalizer.QueryType.ISBN);
        Optional<AladinSearchResult> result = aladinBookService.identify(query);

        assertThat(result).contains(expected);
        verify(aladinClient).lookupBook("9788936439743");
    }

    @Test
    void identifyReturnsEmptyWhenClientFails() {
        when(aladinClient.searchBook("없는책")).thenReturn(Optional.empty());

        InputNormalizer.NormalizedQuery query =
                new InputNormalizer.NormalizedQuery("없는책", InputNormalizer.QueryType.KEYWORD);

        assertThat(aladinBookService.identify(query)).isEmpty();
    }

    @Test
    void getUsedBooksDelegatesToAladinClient() {
        AladinUsedBookResult expected = new AladinUsedBookResult(
                6500, 7000, 6000,
                "https://url1", "https://url2", "https://url3");
        when(aladinClient.getUsedBooks("9788936439743")).thenReturn(expected);

        AladinUsedBookResult result = aladinBookService.getUsedBooks("9788936439743");

        assertThat(result).isEqualTo(expected);
        verify(aladinClient).getUsedBooks("9788936439743");
    }

    @Test
    void getUsedBooksReturnsNullWhenClientReturnsNull() {
        when(aladinClient.getUsedBooks("9788936439743")).thenReturn(null);

        assertThat(aladinBookService.getUsedBooks("9788936439743")).isNull();
    }
}
