package com.checkbook.search.service;

import com.checkbook.aladinstore.domain.AladinStore;
import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinOffStoreResponse;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.search.dto.OffStoreResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AladinBookService {

    private final AladinClient aladinClient;
    private final AladinStoreRepository aladinStoreRepository;

    public Optional<AladinSearchResult> identify(InputNormalizer.NormalizedQuery normalized) {
        if (normalized.type() == InputNormalizer.QueryType.ISBN) {
            return aladinClient.lookupBook(normalized.value());
        }
        return aladinClient.searchBook(normalized.value());
    }

    public AladinUsedBookResult getUsedBooks(String isbn13) {
        return aladinClient.getUsedBooks(isbn13);
    }

    public OffStoreResponse getOffStoreList(String isbn13, double lat, double lon) {
        List<AladinOffStoreResponse.OffStoreInfo> stores = aladinClient.getOffStoreList(isbn13);
        Optional<Long> itemId = Optional.ofNullable(aladinClient.lookupItemId(isbn13))
                .orElse(Optional.empty());

        if (stores.isEmpty()) {
            return new OffStoreResponse(List.of());
        }

        List<String> offCodes = stores.stream()
                .map(AladinOffStoreResponse.OffStoreInfo::offCode)
                .toList();

        Map<String, AladinStore> storeMap = aladinStoreRepository.findByOffCodeIn(offCodes).stream()
                .collect(Collectors.toMap(AladinStore::getOffCode, Function.identity()));

        List<OffStoreResponse.StoreInfo> storeInfos = stores.stream()
                .map(apiStore -> {
                    AladinStore dbStore = storeMap.get(apiStore.offCode());
                    Double distance = null;
                    String address = null;
                    Double storeLat = null;
                    Double storeLon = null;

                    if (dbStore != null) {
                        distance = Math.round(
                                DistanceCalculator.km(lat, lon, dbStore.getLat(), dbStore.getLon()) * 10.0
                        ) / 10.0;
                        address = dbStore.getAddress();
                        storeLat = dbStore.getLat();
                        storeLon = dbStore.getLon();
                    }

                    return new OffStoreResponse.StoreInfo(
                            apiStore.offName(),
                            address,
                            distance,
                            resolveStoreLink(itemId.orElse(null), apiStore),
                            storeLat,
                            storeLon
                    );
                })
                .sorted(Comparator.comparing(
                        OffStoreResponse.StoreInfo::distance,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new OffStoreResponse(storeInfos);
    }

    private String resolveStoreLink(Long itemId, AladinOffStoreResponse.OffStoreInfo apiStore) {
        if (itemId != null && apiStore.offCode() != null && !apiStore.offCode().isBlank()) {
            return "https://www.aladin.co.kr/usedstore/wproduct.aspx?ItemId="
                    + itemId
                    + "&OffCode="
                    + apiStore.offCode()
                    + "&partner=openAPI";
        }
        return apiStore.link();
    }
}
