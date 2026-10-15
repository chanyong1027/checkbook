package com.checkbook.search.service;

import com.checkbook.aladinstore.domain.AladinStore;
import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.search.dto.OffStoreResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AladinBookService {

    private final AladinStoreRepository aladinStoreRepository;

    public OffStoreResponse getOffStoreList(String isbn13, double lat, double lon) {
        if (!InputNormalizer.isValidIsbn13(isbn13)) {
            throw new BusinessException(ErrorCode.NON_STANDARD_ISBN);
        }
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new BusinessException(ErrorCode.INVALID_LOCATION);
        }
        // 반올림 전 실제 거리로 고른다. 재고는 조회하지 않는다(robots.txt 전면 차단) — 매장 링크에서 사용자가 확인
        List<OffStoreResponse.StoreInfo> stores = aladinStoreRepository.findAll().stream()
                .filter(store -> store.getLat() != null && store.getLon() != null
                        && Double.isFinite(store.getLat()) && Double.isFinite(store.getLon())
                        && Math.abs(store.getLat()) <= 90 && Math.abs(store.getLon()) <= 180)
                .sorted(Comparator.comparingDouble((AladinStore store) ->
                                DistanceCalculator.km(lat, lon, store.getLat(), store.getLon()))
                        .thenComparing(AladinStore::getOffCode))
                .limit(5)
                .map(store -> {
                    double distance = Math.round(DistanceCalculator.km(
                            lat, lon, store.getLat(), store.getLon()) * 10.0) / 10.0;
                    String link = "https://www.aladin.co.kr/usedstore/wproduct.aspx?ISBN="
                            + isbn13 + "&OffCode=" + store.getOffCode();
                    return new OffStoreResponse.StoreInfo(
                            store.getName(), store.getAddress(), distance, link,
                            store.getLat(), store.getLon()
                    );
                })
                .toList();
        return new OffStoreResponse(stores);
    }
}
