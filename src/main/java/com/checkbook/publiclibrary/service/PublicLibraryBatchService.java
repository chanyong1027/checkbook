package com.checkbook.publiclibrary.service;

import com.checkbook.common.concurrent.AsyncSubmit;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.BatchStopReason;
import com.checkbook.publiclibrary.dto.LibraryCheckFailure;
import com.checkbook.publiclibrary.dto.LibraryState;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchItem;
import com.checkbook.publiclibrary.dto.PublicLibraryBatchResponse;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D7-1 도서관 배치 조회. 화면이 5곳씩 이어 요청하며 "소장 5곳"을 채운다(결정 2026-11-02 D7-1 기술설계 T1~T4).
 * <p>
 * 기존 {@link PublicLibraryAvailabilityService}(구 화면 더보기)와 다른 점:
 * 후보는 반경 안 전체(20곳 상한 없음), 실패 기관도 UNKNOWN으로 응답에 남김, 예산 소진에도 503 대신 200.
 * 외부 호출은 같은 스냅샷·예산·서킷·풀 경로를 쓴다.
 */
@Slf4j
@Service
public class PublicLibraryBatchService {

    static final int MAX_SAVED_LIBRARIES = 5;

    private final LibraryAvailabilitySnapshotService snapshotService;
    private final PublicLibraryRepository repository;
    private final ExecutorService publicLibraryExecutor;

    @Value("${search.public-library-page-size}")
    private int pageSize;

    @Value("${search.public-library-radius-km:10}")
    private double radiusKm;

    @Value("${search.public-library-fanout-timeout:2200}")
    private long fanoutTimeoutMs;

    public PublicLibraryBatchService(
            LibraryAvailabilitySnapshotService snapshotService,
            PublicLibraryRepository repository,
            @Qualifier("publicLibraryExecutor") ExecutorService publicLibraryExecutor
    ) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.publicLibraryExecutor = publicLibraryExecutor;
    }

    /** 현재 좌표 기준 주변. 한 탐색 동안 화면이 같은 좌표를 보내야 offset이 이어진다. */
    public PublicLibraryBatchResponse nearby(String isbn13, double lat, double lon, int offset) {
        long startNanos = System.nanoTime();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new BusinessException(ErrorCode.INVALID_LOCATION);
        }
        return nearbyPage(isbn13, lat, lon, offset, startNanos);
    }

    /** 사용자가 고른 기준 기관 좌표 기준 주변. 기준 기관 자신(거리 0)도 후보다. */
    public PublicLibraryBatchResponse nearbyFromLibrary(String isbn13, String referenceLibCode, int offset) {
        long startNanos = System.nanoTime();
        PublicLibrary reference = repository.findByLibCode(referenceLibCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNKNOWN_PUBLIC_LIBRARY));
        return nearbyPage(isbn13, reference.getLat(), reference.getLon(), offset, startNanos);
    }

    /** 내 도서관. 요청 순서대로, 거리 없이, 미소장도 그대로 돌려준다. */
    public PublicLibraryBatchResponse saved(String isbn13, List<String> libCodes) {
        long startNanos = System.nanoTime();
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(libCodes));
        if (unique.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PUBLIC_LIBRARY_QUERY);
        }
        if (unique.size() > MAX_SAVED_LIBRARIES) {
            throw new BusinessException(ErrorCode.PUBLIC_LIBRARY_CODES_LIMIT_EXCEEDED);
        }
        Map<String, PublicLibrary> byCode = repository.findByLibCodeIn(unique).stream()
                .collect(Collectors.toMap(PublicLibrary::getLibCode, Function.identity(), (a, b) -> a));
        if (!byCode.keySet().containsAll(unique)) {
            // 오래된 저장 코드. 몰래 빼고 조회하지 않는다 — 화면이 재선택을 안내한다
            throw new BusinessException(ErrorCode.UNKNOWN_PUBLIC_LIBRARY);
        }
        List<Target> targets = unique.stream()
                .map(code -> new Target(byCode.get(code), null))
                .toList();
        return check(isbn13, targets, null, startNanos);
    }

    private PublicLibraryBatchResponse nearbyPage(String isbn13, double lat, double lon, int offset, long startNanos) {
        List<PublicLibrary> candidates = repository.findWithinRadius(lat, lon, radiusKm);
        int total = candidates.size();
        int from = Math.min(offset, total);
        int to = (int) Math.min((long) offset + pageSize, total);
        List<Target> targets = candidates.subList(from, to).stream()
                .map(library -> new Target(library,
                        DistanceCalculator.km(lat, lon, library.getLat(), library.getLon())))
                .toList();
        Integer nextOffset = to < total ? to : null;
        return check(isbn13, targets, nextOffset, startNanos);
    }

    private PublicLibraryBatchResponse check(String isbn13, List<Target> targets, Integer nextOffset, long startNanos) {
        // 마감은 서비스 진입부터 잰다 — 후보 선정·큐 대기도 사용자가 기다리는 시간이다
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(fanoutTimeoutMs);
        // 마감을 두 번 확인한다: 제출 전(후보 조회가 시간을 다 썼으면 넣지 않음)과 워커 실행 직전
        // (늦게 시작한 워커는 cancel(false)로 못 멈추므로 스스로 포기). 둘 다 정보나루를 부르지 않고 TIMEOUT이 된다
        List<CompletableFuture<LibraryAvailabilityResult>> futures = targets.stream()
                .map(target -> {
                    if (System.nanoTime() - deadlineNanos >= 0) {
                        CompletableFuture<LibraryAvailabilityResult> expired = new CompletableFuture<>();
                        expired.cancel(false);
                        return expired;
                    }
                    return AsyncSubmit.submitSafely(() -> {
                        if (System.nanoTime() - deadlineNanos >= 0) {
                            throw new CancellationException("배치 마감 뒤 시작");
                        }
                        return snapshotService.getAvailability(isbn13, target.library().getLibCode());
                    }, publicLibraryExecutor);
                })
                .toList();

        long remainingNanos = deadlineNanos - System.nanoTime();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.warn("공공도서관 배치 마감 {}ms 초과", fanoutTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 개별 실패는 아래에서 기관별로 읽는다(allOf는 하나만 실패해도 예외로 끝난다)
        }

        // 아직 큐에서 시작하지 않은 작업은 취소해 정보나루를 부르지 않게 한다(시작 전 결과가 정해지면
        // supplyAsync는 supplier를 실행하지 않는다). 이미 시작한 호출은 멈추지 않는다 — cancel은 인터럽트하지 않음
        // 취소된 작업도 워커가 꺼낼 때까지 큐 자리는 차지한다(꺼내면 바로 끝난다). 큐에서 직접 빼려면 감싼 Runnable을 알아야 해 두지 않았다
        futures.forEach(future -> future.cancel(false));

        List<PublicLibraryBatchItem> items = new ArrayList<>(targets.size());
        boolean anyRejected = false;
        boolean anyUnfinished = false;
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            CompletableFuture<LibraryAvailabilityResult> future = futures.get(i);
            LibraryState state;
            LibraryCheckFailure reason = null;
            if (future.isCancelled() || unwrap(future) instanceof CancellationException) {
                // 마감까지 못 끝난 작업(시작 전이면 호출도 안 했고, 시작했으면 결과만 버린다).
                // 워커가 마감 뒤라 스스로 포기한 경우는 CompletionException에 싸여 와서 원인으로 본다
                anyUnfinished = true;
                state = LibraryState.UNKNOWN;
                reason = LibraryCheckFailure.TIMEOUT;
            } else if (future.isCompletedExceptionally()) {
                Throwable cause = unwrap(future);
                boolean rejected = cause instanceof RejectedExecutionException;
                anyRejected |= rejected;
                if (!rejected) {
                    log.warn("공공도서관 배치 조회 예외: libCode={}", target.library().getLibCode(), cause);
                }
                state = LibraryState.UNKNOWN;
                reason = rejected ? LibraryCheckFailure.POOL_BUSY : LibraryCheckFailure.UPSTREAM_ERROR;
            } else {
                LibraryAvailabilityResult result = future.join();
                if (result.sourceStatus() == SnapshotSourceStatus.FAILED) {
                    state = LibraryState.UNKNOWN;
                    reason = LibraryCheckFailure.UPSTREAM_ERROR;
                } else {
                    state = LibraryState.of(result.hasBook(), result.loanAvailable());
                }
            }
            items.add(toItem(target, state, reason));
        }

        BatchStopReason stopReason = anyRejected ? BatchStopReason.POOL_BUSY
                : anyUnfinished ? BatchStopReason.BATCH_DEADLINE
                : BatchStopReason.NONE;
        return new PublicLibraryBatchResponse(items, nextOffset, stopReason);
    }

    private static Throwable unwrap(CompletableFuture<?> future) {
        try {
            future.join();
            return null;
        } catch (CompletionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static PublicLibraryBatchItem toItem(Target target, LibraryState state, LibraryCheckFailure reason) {
        PublicLibrary library = target.library();
        Double distance = target.distanceKm() == null ? null : Math.round(target.distanceKm() * 10.0) / 10.0;
        return new PublicLibraryBatchItem(
                library.getLibCode(),
                library.getName(),
                library.getAddress(),
                library.getLat(),
                library.getLon(),
                distance,
                library.getHomepage(),
                state,
                reason);
    }

    /** 조회 대상 하나. distanceKm는 반올림 전 값(정렬은 이미 원값으로 끝났다). */
    private record Target(PublicLibrary library, Double distanceKm) {
    }
}
