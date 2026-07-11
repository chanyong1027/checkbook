package com.checkbook.publiclibrary.service;

import com.checkbook.common.concurrent.AsyncSubmit;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class PublicLibraryAvailabilityService {

    private final LibraryAvailabilitySnapshotService snapshotService;
    private final PublicLibraryRepository publicLibraryRepository;
    private final ExecutorService publicLibraryExecutor;

    @Value("${search.public-library-page-size}")
    private int pageSize;

    @Value("${search.public-library-max-count}")
    private int maxCount;

    @Value("${search.public-library-fanout-timeout:2200}")
    private long fanoutTimeoutMs;

    public PublicLibraryAvailabilityService(
            LibraryAvailabilitySnapshotService snapshotService,
            PublicLibraryRepository publicLibraryRepository,
            @Qualifier("publicLibraryExecutor") ExecutorService publicLibraryExecutor
    ) {
        this.snapshotService = snapshotService;
        this.publicLibraryRepository = publicLibraryRepository;
        this.publicLibraryExecutor = publicLibraryExecutor;
    }

    /**
     * 근처 후보(max-count 캡)에서 [offset, offset+page-size) 구간만 bookExist fan-out.
     * @param offset 0 이상(컨트롤러 @Min(0) / 호출부 보장). lat/lon 범위 위반은 400.
     */
    public PublicLibraryAvailabilityPage fetch(String isbn13, double lat, double lon, int offset) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new BusinessException(ErrorCode.INVALID_LOCATION);
        }

        List<PublicLibrary> candidates = publicLibraryRepository.findNearest(lat, lon, maxCount);
        int total = candidates.size();
        int from = Math.min(offset, total);
        int to = (int) Math.min((long) offset + pageSize, total);
        List<PublicLibrary> targets = candidates.subList(from, to);

        List<CompletableFuture<PublicLibraryInfo>> futures = targets.stream()
                .map(library -> AsyncSubmit.submitSafely(() -> toInfo(isbn13, lat, lon, library), publicLibraryExecutor)
                        .exceptionally(exception -> {
                            log.warn("bookExist 호출 실패: {} - 건너뜀", library.getName(), exception);
                            return null;
                        }))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(fanoutTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("공공도서관 bookExist fan-out 타임아웃 {}ms", fanoutTimeoutMs);
        } catch (Exception e) {
            log.warn("공공도서관 bookExist fan-out 대기 중 오류", e);
        }

        List<PublicLibraryInfo> libraries = futures.stream()
                .filter(CompletableFuture::isDone)
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(PublicLibraryInfo::distance))
                .toList();

        int failedCount = targets.size() - libraries.size();
        boolean hasMoreLibraries = to < total;
        Integer nextOffset = hasMoreLibraries ? to : null;

        return new PublicLibraryAvailabilityPage(
                libraries, offset, total, nextOffset, hasMoreLibraries, failedCount);
    }

    /** availability 조회 실패(sourceStatus=FAILED)는 null 반환 → 드롭 + failedCount 집계. */
    private PublicLibraryInfo toInfo(String isbn13, double lat, double lon, PublicLibrary library) {
        LibraryAvailabilityResult availability = snapshotService.getAvailability(isbn13, library.getLibCode());
        if (availability.sourceStatus() == SnapshotSourceStatus.FAILED) {
            return null;
        }
        double distance = Math.round(
                DistanceCalculator.km(lat, lon, library.getLat(), library.getLon()) * 10.0
        ) / 10.0;
        return new PublicLibraryInfo(
                library.getName(),
                availability.hasBook(),
                availability.loanAvailable(),
                library.getAddress(),
                library.getLat(),
                library.getLon(),
                distance,
                library.getHomepage()
        );
    }
}
