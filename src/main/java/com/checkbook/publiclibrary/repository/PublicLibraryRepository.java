package com.checkbook.publiclibrary.repository;

import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface PublicLibraryRepository extends JpaRepository<PublicLibrary, Long> {

    Optional<PublicLibrary> findByLibCode(String libCode);

    boolean existsByLibCode(String libCode);

    default List<PublicLibrary> findNearest(double lat, double lon, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return findAll().stream()
                .filter(library -> library.getLat() != null && library.getLon() != null)
                .sorted(Comparator.comparingDouble(
                        library -> DistanceCalculator.km(lat, lon, library.getLat(), library.getLon())))
                .limit(limit)
                .toList();
    }
}
