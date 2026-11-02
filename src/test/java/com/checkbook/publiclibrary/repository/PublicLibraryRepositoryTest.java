package com.checkbook.publiclibrary.repository;

import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.junit.jupiter.api.Test;
import com.checkbook.common.util.DistanceCalculator;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicLibraryRepositoryTest {

    private final PublicLibraryRepository repository =
            mock(PublicLibraryRepository.class, Answers.CALLS_REAL_METHODS);

    @Test
    void findNearestReturnsClosestFirst() {
        List<PublicLibrary> libraries = List.of(
                PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build(),
                PublicLibrary.builder()
                .libCode("L2")
                .name("부산도서관")
                .lat(35.160)
                .lon(129.160)
                .build()
        );

        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(libraries);

        List<PublicLibrary> result = repository.findNearest(37.5666, 126.9784, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("종로도서관");
    }

    @Test
    void findNearestReturnsEmptyWhenLimitIsZero() {
        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build()));

        assertThat(repository.findNearest(37.5666, 126.9784, 0)).isEmpty();
    }

    @Test
    void findNearestExpandsBoundingBoxUntilCandidatesFound() {
        PublicLibrary library = PublicLibrary.builder()
                .libCode("L1")
                .name("종로도서관")
                .lat(37.570)
                .lon(126.982)
                .build();

        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of())
                .thenReturn(List.of())
                .thenReturn(List.of(library));

        List<PublicLibrary> result = repository.findNearest(37.5666, 126.9784, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("종로도서관");
    }

    @Test
    void findNearestBreaksDistanceTiesByLibCode() {
        List<PublicLibrary> libraries = List.of(
                PublicLibrary.builder().libCode("B").name("나").lat(37.5665).lon(126.9780).build(),
                PublicLibrary.builder().libCode("A").name("가").lat(37.5665).lon(126.9780).build()
        );

        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(libraries);

        List<PublicLibrary> result = repository.findNearest(37.5665, 126.9780, 10);

        assertThat(result).extracting(PublicLibrary::getLibCode).containsExactly("A", "B");
    }

    // --- 반경 조회(D7-1 주변 탐색) ---

    private static final double CENTER_LAT = 37.5665;
    private static final double CENTER_LON = 126.9780;

    private static PublicLibrary at(String code, double lat, double lon) {
        return PublicLibrary.builder().libCode(code).name(code).lat(lat).lon(lon).build();
    }

    @Test
    void findWithinRadius는_네모_안이어도_반경_밖_기관은_뺀다() {
        // 네모 모서리(대각선 약 14km)는 네모 안이지만 반경 10km 밖이다
        double latDelta = 10.0 / 111.0;
        double lonDelta = 10.0 / (111.0 * Math.cos(Math.toRadians(CENTER_LAT)));
        PublicLibrary corner = at("CORNER", CENTER_LAT + latDelta * 0.95, CENTER_LON + lonDelta * 0.95);
        PublicLibrary inside = at("IN", CENTER_LAT + 0.08, CENTER_LON); // 약 8.9km
        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(corner, inside));

        List<PublicLibrary> result = repository.findWithinRadius(CENTER_LAT, CENTER_LON, 10.0);

        assertThat(result).extracting(PublicLibrary::getLibCode).containsExactly("IN");
    }

    @Test
    void findWithinRadius의_네모는_반경_원을_모두_덮는다() {
        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of());

        repository.findWithinRadius(CENTER_LAT, CENTER_LON, 10.0);

        ArgumentCaptor<Double> minLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxLat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> minLon = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> maxLon = ArgumentCaptor.forClass(Double.class);
        verify(repository).findByLatBetweenAndLonBetween(
                minLat.capture(), maxLat.capture(), minLon.capture(), maxLon.capture());
        // 네모 네 변까지의 실제 거리가 모두 반경 이상이어야 원 안의 기관을 놓치지 않는다(사각형 반례 방지)
        assertThat(DistanceCalculator.km(CENTER_LAT, CENTER_LON, minLat.getValue(), CENTER_LON)).isGreaterThanOrEqualTo(10.0);
        assertThat(DistanceCalculator.km(CENTER_LAT, CENTER_LON, maxLat.getValue(), CENTER_LON)).isGreaterThanOrEqualTo(10.0);
        assertThat(DistanceCalculator.km(CENTER_LAT, CENTER_LON, CENTER_LAT, minLon.getValue())).isGreaterThanOrEqualTo(10.0);
        assertThat(DistanceCalculator.km(CENTER_LAT, CENTER_LON, CENTER_LAT, maxLon.getValue())).isGreaterThanOrEqualTo(10.0);
    }

    @Test
    void findWithinRadius는_거리순_동률은_코드순으로_정렬한다() {
        when(repository.findByLatBetweenAndLonBetween(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(
                        at("FAR", CENTER_LAT + 0.05, CENTER_LON),
                        at("B", CENTER_LAT + 0.01, CENTER_LON),
                        at("A", CENTER_LAT + 0.01, CENTER_LON),
                        at("SELF", CENTER_LAT, CENTER_LON)));

        List<PublicLibrary> result = repository.findWithinRadius(CENTER_LAT, CENTER_LON, 10.0);

        assertThat(result).extracting(PublicLibrary::getLibCode).containsExactly("SELF", "A", "B", "FAR");
    }
}
