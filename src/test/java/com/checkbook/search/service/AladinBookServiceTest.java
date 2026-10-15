package com.checkbook.search.service;

import com.checkbook.aladinstore.domain.AladinStore;
import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.search.dto.OffStoreResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 가까운 매장 목록. robots.txt 전면 차단(10-15)으로 재고는 조회하지 않고 매장별 확인 링크만 준다.
 */
@ExtendWith(MockitoExtension.class)
class AladinBookServiceTest {

    private static final String ISBN = "9788936434120";

    @Mock
    private AladinStoreRepository aladinStoreRepository;

    private AladinBookService aladinBookService;

    @BeforeEach
    void setUp() {
        // 외부 클라이언트 없이 저장소만으로 만들어진다 — 알라딘 웹·API를 부르지 않는다
        aladinBookService = new AladinBookService(aladinStoreRepository);
    }

    @Test
    void returnsNearestFiveWithStoreLinks() {
        List<AladinStore> stores = IntStream.rangeClosed(1, 7)
                .mapToObj(number -> AladinStore.builder().offCode("store" + number)
                        .name("매장" + number).address("주소")
                        .lat(37.5 + number * 0.01).lon(127.0).build())
                .toList().reversed();
        when(aladinStoreRepository.findAll()).thenReturn(stores);

        OffStoreResponse result = aladinBookService.getOffStoreList(ISBN, 37.5, 127.0);

        assertThat(result.stores()).extracting(OffStoreResponse.StoreInfo::storeName)
                .containsExactly("매장1", "매장2", "매장3", "매장4", "매장5");
        assertThat(result.stores().get(0).link()).isEqualTo(
                "https://www.aladin.co.kr/usedstore/wproduct.aspx?ISBN=" + ISBN + "&OffCode=store1");
        assertThat(result.stores().get(0).distance()).isEqualTo(1.1);
    }

    @Test
    void storeInfoCarriesNoStockFields() {
        // 재고를 확인하지 않으므로 응답에도 재고 필드를 두지 않는다(있음·없음으로 오해될 여지 제거)
        assertThat(Arrays.stream(OffStoreResponse.StoreInfo.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("storeName", "address", "distance", "link", "latitude", "longitude");
    }

    @Test
    void newLocationChangesSelectedStores() {
        AladinStore north = AladinStore.builder().offCode("north").name("북쪽").address("주소")
                .lat(38.0).lon(127.0).build();
        AladinStore south = AladinStore.builder().offCode("south").name("남쪽").address("주소")
                .lat(37.0).lon(127.0).build();
        when(aladinStoreRepository.findAll()).thenReturn(List.of(north, south));

        assertThat(aladinBookService.getOffStoreList(ISBN, 37.0, 127.0).stores().get(0).storeName())
                .isEqualTo("남쪽");
        assertThat(aladinBookService.getOffStoreList(ISBN, 38.0, 127.0).stores().get(0).storeName())
                .isEqualTo("북쪽");
    }

    @Test
    void storesWithoutValidCoordinatesAreSkipped() {
        AladinStore noCoordinates = AladinStore.builder().offCode("none").name("좌표없음").address("주소").build();
        AladinStore valid = AladinStore.builder().offCode("ok").name("정상").address("주소")
                .lat(37.5).lon(127.0).build();
        when(aladinStoreRepository.findAll()).thenReturn(List.of(noCoordinates, valid));

        assertThat(aladinBookService.getOffStoreList(ISBN, 37.5, 127.0).stores())
                .extracting(OffStoreResponse.StoreInfo::storeName).containsExactly("정상");
    }

    @Test
    void sameDistanceIsOrderedByStoreCode() {
        // 같은 거리면 매장 코드 순 — 요청마다 순서가 흔들리지 않게
        AladinStore b = AladinStore.builder().offCode("b").name("비").address("주소").lat(37.6).lon(127.0).build();
        AladinStore a = AladinStore.builder().offCode("a").name("에이").address("주소").lat(37.6).lon(127.0).build();
        when(aladinStoreRepository.findAll()).thenReturn(List.of(b, a));

        assertThat(aladinBookService.getOffStoreList(ISBN, 37.5, 127.0).stores())
                .extracting(OffStoreResponse.StoreInfo::storeName).containsExactly("에이", "비");
    }

    @Test
    void noStoresReturnsEmptyList() {
        when(aladinStoreRepository.findAll()).thenReturn(List.of());
        assertThat(aladinBookService.getOffStoreList(ISBN, 37.5, 127.0).stores()).isEmpty();
    }

    @Test
    void invalidIsbnAndNonFiniteOrOutOfRangeLocationAreRejectedBeforeLookup() {
        assertThatThrownBy(() -> aladinBookService.getOffStoreList("2004566000649", 37.5, 127.0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NON_STANDARD_ISBN);
        for (double latitude : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 91}) {
            assertThatThrownBy(() -> aladinBookService.getOffStoreList(ISBN, latitude, 127.0))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_LOCATION);
        }
        assertThatThrownBy(() -> aladinBookService.getOffStoreList(ISBN, 37.5, -181))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_LOCATION);
        verifyNoInteractions(aladinStoreRepository);
    }
}
