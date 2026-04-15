package com.checkbook.search.service;

import com.checkbook.aladinstore.domain.AladinStore;
import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinOffStoreResponse;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.search.dto.OffStoreResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void getOffStoreListReturnsSortedByDistance() {
        List<AladinOffStoreResponse.OffStoreInfo> apiStores = List.of(
                new AladinOffStoreResponse.OffStoreInfo("Jongno", "종로점", "https://link1"),
                new AladinOffStoreResponse.OffStoreInfo("Geondae", "건대점", "https://link3")
        );
        when(aladinClient.getOffStoreList("9788936439743")).thenReturn(apiStores);
        when(aladinClient.lookupItemId("9788936439743")).thenReturn(Optional.of(123L));

        AladinStore jongro = AladinStore.builder()
                .offCode("Jongno").name("종로점").address("서울 종로구").lat(37.5700).lon(126.9920).build();
        AladinStore kondae = AladinStore.builder()
                .offCode("Geondae").name("건대점").address("서울 광진구").lat(37.5407).lon(127.0700).build();
        when(aladinStoreRepository.findByOffCodeIn(List.of("Jongno", "Geondae")))
                .thenReturn(List.of(jongro, kondae));

        OffStoreResponse result = aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780);

        assertThat(result.stores()).hasSize(2);
        assertThat(result.stores().get(0).storeName()).isEqualTo("종로점");
        assertThat(result.stores().get(0).distance()).isEqualTo(1.3);
        assertThat(result.stores().get(0).link())
                .isEqualTo("https://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId=123&OffCode=Jongno&partner=openAPI");
        assertThat(result.stores().get(1).storeName()).isEqualTo("건대점");
        assertThat(result.stores().get(1).distance()).isGreaterThan(result.stores().get(0).distance());
        assertThat(result.stores().get(1).link())
                .isEqualTo("https://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId=123&OffCode=Geondae&partner=openAPI");
    }

    @Test
    void getOffStoreListEmptyReturnsEmptyList() {
        when(aladinClient.getOffStoreList("9788936439743")).thenReturn(List.of());

        OffStoreResponse result = aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780);

        assertThat(result.stores()).isEmpty();
    }

    @Test
    void getOffStoreListUnmatchedOffCodeReturnsStoreWithoutLocation() {
        List<AladinOffStoreResponse.OffStoreInfo> apiStores = List.of(
                new AladinOffStoreResponse.OffStoreInfo("NewStore", "신규점", "https://link-new")
        );
        when(aladinClient.getOffStoreList("9788936439743")).thenReturn(apiStores);
        when(aladinClient.lookupItemId("9788936439743")).thenReturn(Optional.of(456L));
        when(aladinStoreRepository.findByOffCodeIn(List.of("NewStore"))).thenReturn(List.of());

        OffStoreResponse result = aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780);

        assertThat(result.stores()).hasSize(1);
        assertThat(result.stores().get(0).storeName()).isEqualTo("신규점");
        assertThat(result.stores().get(0).distance()).isNull();
        assertThat(result.stores().get(0).address()).isNull();
        assertThat(result.stores().get(0).link())
                .isEqualTo("https://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId=456&OffCode=NewStore&partner=openAPI");
    }

    @Test
    void getOffStoreListFallsBackToApiLinkWhenItemIdLookupFails() {
        List<AladinOffStoreResponse.OffStoreInfo> apiStores = List.of(
                new AladinOffStoreResponse.OffStoreInfo("Jongno", "종로점", "https://fallback-link")
        );
        when(aladinClient.getOffStoreList("9788936439743")).thenReturn(apiStores);
        when(aladinClient.lookupItemId("9788936439743")).thenReturn(Optional.empty());
        when(aladinStoreRepository.findByOffCodeIn(List.of("Jongno"))).thenReturn(List.of());

        OffStoreResponse result = aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780);

        assertThat(result.stores()).hasSize(1);
        assertThat(result.stores().get(0).link()).isEqualTo("https://fallback-link");
    }

    @Test
    void getOffStoreListApiFailureThrows() {
        when(aladinClient.getOffStoreList("9788936439743"))
                .thenThrow(new IllegalStateException("알라딘 매장 재고 조회 오류"));

        assertThatThrownBy(() -> aladinBookService.getOffStoreList("9788936439743", 37.5665, 126.9780))
                .isInstanceOf(IllegalStateException.class);
    }
}
