package com.checkbook.publiclibrary.repository;

import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface PublicLibraryRepository extends JpaRepository<PublicLibrary, Long> {

    double[] SEARCH_RADIUS_KM_STEPS = {5.0, 20.0, 80.0, 320.0, 1280.0};

    Optional<PublicLibrary> findByLibCode(String libCode);

    boolean existsByLibCode(String libCode);

    List<PublicLibrary> findByLatBetweenAndLonBetween(
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
                .sorted(Comparator.comparingDouble(
                        library -> DistanceCalculator.km(lat, lon, library.getLat(), library.getLon())))
                .limit(limit)
                .toList();
    }

    private List<PublicLibrary> findWithinBoundingBox(double lat, double lon, double radiusKm) {
        double latDelta = radiusKm / 111.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double normalizedCosLat = Math.max(Math.abs(cosLat), 0.1);
        double lonDelta = radiusKm / (111.0 * normalizedCosLat);

        return findByLatBetweenAndLonBetween(
                lat - latDelta,
                lat + latDelta,
                lon - lonDelta,
                lon + lonDelta
        );
    }
}
