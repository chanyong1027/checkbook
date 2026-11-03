package com.checkbook.publiclibrary.repository;

import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface PublicLibraryRepository extends JpaRepository<PublicLibrary, Long> {

    double[] SEARCH_RADIUS_KM_STEPS = {5.0, 20.0, 80.0, 320.0, 1280.0};

    Optional<PublicLibrary> findByLibCode(String libCode);

    boolean existsByLibCode(String libCode);

    /** 주변 검색 후보. 정보나루 참여 목록에서 빠진(비활성) 도서관은 넣지 않는다. */
    List<PublicLibrary> findByActiveTrueAndLatBetweenAndLonBetween(
            double minLat,
            double maxLat,
            double minLon,
            double maxLon
    );

    default List<PublicLibrary> findNearest(double lat, double lon, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        List<PublicLibrary> candidates = List.of();
        for (double radiusKm : SEARCH_RADIUS_KM_STEPS) {
            candidates = findWithinBoundingBox(lat, lon, radiusKm);
            if (candidates.size() >= limit) {
                break;
            }
        }

        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((PublicLibrary library) ->
                                DistanceCalculator.km(lat, lon, library.getLat(), library.getLon()))
                        .thenComparing(PublicLibrary::getLibCode))
                .limit(limit)
                .toList();
    }

    /**
     * 기준점에서 직선거리 radiusKm 이내 기관을 거리순(동률은 libCode순)으로 돌려준다(D7-1 주변 탐색).
     * <p>
     * 반경을 덮는 네모를 한 번 읽고 실제 거리로 거른다. 원이 네모 안에 들어가므로 {@link #findNearest}의
     * "네모 안 후보가 limit개면 멈춤" 때문에 네모 밖의 더 가까운 기관을 빠뜨리는 일이 없다.
     * 네모의 1도 = 111.0km 근사는 실제(약 111.19km)보다 작아 네모가 반경보다 조금 넓다 — 안전한 쪽 오차.
     */
    default List<PublicLibrary> findWithinRadius(double lat, double lon, double radiusKm) {
        return findWithinBoundingBox(lat, lon, radiusKm).stream()
                .filter(library -> DistanceCalculator.km(lat, lon, library.getLat(), library.getLon()) <= radiusKm)
                .sorted(Comparator
                        .comparingDouble((PublicLibrary library) ->
                                DistanceCalculator.km(lat, lon, library.getLat(), library.getLon()))
                        .thenComparing(PublicLibrary::getLibCode))
                .toList();
    }

    List<PublicLibrary> findByLibCodeIn(Collection<String> libCodes);

    /**
     * 기관 선택용 검색(D7-1). 이름 또는 주소에 keyword가 들어간 활성 기관. 정렬은 Pageable이 정한다.
     * 파생 쿼리라 (활성 그리고 이름) 또는 (활성 그리고 주소)로 쓴다 — Containing의 %·_ 이스케이프를 그대로 받으려고
     */
    default Slice<PublicLibrary> searchActiveByNameOrAddress(String keyword, Pageable pageable) {
        return findByActiveTrueAndNameContainingIgnoreCaseOrActiveTrueAndAddressContainingIgnoreCase(
                keyword, keyword, pageable);
    }

    Slice<PublicLibrary> findByActiveTrueAndNameContainingIgnoreCaseOrActiveTrueAndAddressContainingIgnoreCase(
            String nameKeyword, String addressKeyword, Pageable pageable);

    private List<PublicLibrary> findWithinBoundingBox(double lat, double lon, double radiusKm) {
        double latDelta = radiusKm / 111.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double normalizedCosLat = Math.max(Math.abs(cosLat), 0.1);
        double lonDelta = radiusKm / (111.0 * normalizedCosLat);

        return findByActiveTrueAndLatBetweenAndLonBetween(
                lat - latDelta,
                lat + latDelta,
                lon - lonDelta,
                lon + lonDelta
        );
    }
}
