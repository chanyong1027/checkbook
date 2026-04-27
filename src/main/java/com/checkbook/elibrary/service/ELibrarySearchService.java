package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.elibrary.client.ELibClient;
import com.checkbook.elibrary.client.ELibClientResolver;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.dto.ELibrarySearchStatus;
import com.checkbook.elibrary.repository.ELibraryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ELibrarySearchService {

    private final ELibraryRepository eLibraryRepository;
    private final ELibClientResolver eLibClientResolver;
    private final ExecutorService eLibraryExecutor;
    private final ELibraryBookMatcher matcher;

    @Value("${elibrary.per-library-timeout:15000}")
    private long perLibraryTimeoutMs = 15_000;

    @Value("${elibrary.total-timeout:20000}")
    private long totalTimeoutMs = 20_000;

    public ELibrarySearchService(
            ELibraryRepository eLibraryRepository,
            ELibClientResolver eLibClientResolver,
            @Qualifier("eLibraryExecutor") ExecutorService eLibraryExecutor,
            ELibraryBookMatcher matcher
    ) {
        this.eLibraryRepository = eLibraryRepository;
        this.eLibClientResolver = eLibClientResolver;
        this.eLibraryExecutor = eLibraryExecutor;
        this.matcher = matcher;
    }

    public ELibrarySearchResponse search(String title, String author, String libraryIds) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_KEYWORD);
        }
        List<Long> ids = parseLibraryIds(libraryIds);
        ELibraryBookMatcher.Selected selected = new ELibraryBookMatcher.Selected(title, author);

        long startAll = System.currentTimeMillis();

        List<ELibrary> foundLibraries = eLibraryRepository.findAllById(ids);
        Map<Long, ELibrary> foundMap = foundLibraries.stream()
                .collect(Collectors.toMap(ELibrary::getId, library -> library));

        List<ELibrarySearchResponse.ELibraryFailureDetail> failures = new ArrayList<>();
        List<Long> validIds = new ArrayList<>();

        for (Long id : ids) {
            ELibrary library = foundMap.get(id);
            if (library == null) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, null, "존재하지 않는 도서관"));
            } else if (library.getStatus() != ELibraryStatus.ACTIVE) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, library.getName(), "비활성화된 도서관"));
            } else if (library.isLoginRequired()) {
                failures.add(new ELibrarySearchResponse.ELibraryFailureDetail(id, library.getName(), "로그인 필요 도서관"));
            } else {
                validIds.add(id);
            }
        }

        Map<Long, ScrapeTask> futureMap = new LinkedHashMap<>();
        for (Long id : validIds) {
            ELibrary library = foundMap.get(id);
            long startedAt = System.currentTimeMillis();
            futureMap.put(id, new ScrapeTask(
                    CompletableFuture
                            .supplyAsync(() -> searchBooks(library, selected), eLibraryExecutor)
                            .orTimeout(perLibraryTimeoutMs, TimeUnit.MILLISECONDS),
                    startedAt
            ));
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(
                futureMap.values().stream()
                        .map(ScrapeTask::future)
                        .toArray(CompletableFuture[]::new)
        );

        try {
            all.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("전자도서관 전체 타임아웃 {}ms", totalTimeoutMs);
        } catch (Exception e) {
            log.warn("전자도서관 검색 대기 중 오류", e);
        }

        List<ELibrarySearchResponse.ELibraryResult> results = new ArrayList<>();
        for (Long id : validIds) {
            ELibrary library = foundMap.get(id);
            ScrapeTask scrapeTask = futureMap.get(id);
            results.add(toResult(library, scrapeTask));
        }

        long totalElapsed = System.currentTimeMillis() - startAll;
        return new ELibrarySearchResponse(
                results,
                new ELibrarySearchResponse.ELibrarySearchMetadata(
                        totalElapsed,
                        LocalDateTime.now(),
                        failures
                )
        );
    }

    private ScrapeOutcome searchBooks(ELibrary library, ELibraryBookMatcher.Selected selected) {
        long start = System.currentTimeMillis();
        ELibClient client = eLibClientResolver.resolve(library.getVendorType());
        List<ELibrarySearchResponse.ELibraryBook> raw = client.search(
                library.getBaseUrl(),
                selected.title()
        );

        List<ELibrarySearchResponse.ELibraryBook> kept = new ArrayList<>();
        Map<ELibraryBookMatcher.MatchPath, Integer> pathCounts = new EnumMap<>(ELibraryBookMatcher.MatchPath.class);
        for (ELibrarySearchResponse.ELibraryBook book : raw) {
            ELibraryBookMatcher.MatchResult r = matcher.match(book, selected);
            if (r.matched()) {
                kept.add(book);
                pathCounts.merge(r.path(), 1, Integer::sum);
            }
        }

        logFilterSignal(library, selected, raw, kept, pathCounts);

        return new ScrapeOutcome(kept, System.currentTimeMillis() - start);
    }

    private void logFilterSignal(
            ELibrary library,
            ELibraryBookMatcher.Selected selected,
            List<ELibrarySearchResponse.ELibraryBook> raw,
            List<ELibrarySearchResponse.ELibraryBook> kept,
            Map<ELibraryBookMatcher.MatchPath, Integer> pathCounts
    ) {
        String caseType;
        if (!raw.isEmpty() && kept.isEmpty()) {
            caseType = "filtered_to_zero";
        } else if (kept.size() >= 2) {
            caseType = "multiple_kept";
        } else if (kept.size() == 1
                && pathCounts.getOrDefault(ELibraryBookMatcher.MatchPath.TITLE_ONLY, 0) > 0) {
            caseType = "title_only_pass";
        } else {
            return; // normal single-pass — don't log
        }

        List<String> rawTitles = caseType.equals("filtered_to_zero")
                ? raw.stream().map(ELibrarySearchResponse.ELibraryBook::title).limit(10).toList()
                : List.of();
        List<String> keptTitles = kept.stream()
                .map(ELibrarySearchResponse.ELibraryBook::title).limit(10).toList();

        String pathCountsStr = pathCounts.entrySet().stream()
                .map(e -> e.getKey().name() + ":" + e.getValue())
                .collect(Collectors.joining(","));

        log.info("elibraryFilterSignal case={} libraryId={} libraryName={} vendor={} "
                        + "selectedTitle={} selectedAuthor={} vendorQuery={} "
                        + "rawCount={} keptCount={} rawTitles={} keptTitles={} matchPathCounts={} "
                        + "normSelectedTitle={} normSelectedPrefix={} selAuthorTokens={}",
                caseType, library.getId(), library.getName(), library.getVendorType(),
                selected.title(), selected.author(), selected.title(),
                raw.size(), kept.size(), rawTitles, keptTitles, pathCountsStr,
                matcher.debugNormTitle(selected.title()),
                matcher.debugNormTitlePrefix(selected.title()),
                matcher.debugAuthorTokens(selected.author()));
    }

    private ELibrarySearchResponse.ELibraryResult toResult(
            ELibrary library,
            ScrapeTask scrapeTask
    ) {
        CompletableFuture<ScrapeOutcome> future = scrapeTask.future();
        if (!future.isDone()) {
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
            return timedOutResult(library, elapsedMs);
        }

        try {
            ScrapeOutcome outcome = future.join();
            return new ELibrarySearchResponse.ELibraryResult(
                    library.getId(),
                    library.getName(),
                    library.getVendorType().name(),
                    outcome.books(),
                    ELibrarySearchStatus.SUCCESS,
                    outcome.elapsedMs()
            );
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TimeoutException) {
                long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
                return timedOutResult(library, elapsedMs);
            }

            log.warn("전자도서관 검색 실패: {}", library.getName(), cause);
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - scrapeTask.startedAt());
            return new ELibrarySearchResponse.ELibraryResult(
                    library.getId(),
                    library.getName(),
                    library.getVendorType().name(),
                    List.of(),
                    ELibrarySearchStatus.FAILED,
                    elapsedMs
            );
        }
    }

    private ELibrarySearchResponse.ELibraryResult timedOutResult(ELibrary library, long elapsedMs) {
        return new ELibrarySearchResponse.ELibraryResult(
                library.getId(),
                library.getName(),
                library.getVendorType().name(),
                List.of(),
                ELibrarySearchStatus.TIMEOUT,
                elapsedMs
        );
    }

    private List<Long> parseLibraryIds(String libraryIds) {
        if (libraryIds == null || libraryIds.isBlank()) {
            throw new BusinessException(ErrorCode.LIBRARY_IDS_REQUIRED);
        }

        String[] parts = libraryIds.split(",");
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            try {
                long id = Long.parseLong(trimmed);
                if (id <= 0) {
                    throw new BusinessException(ErrorCode.INVALID_LIBRARY_ID);
                }
                uniqueIds.add(id);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.INVALID_LIBRARY_ID);
            }
        }

        if (uniqueIds.size() > 20) {
            throw new BusinessException(ErrorCode.LIBRARY_IDS_LIMIT_EXCEEDED);
        }

        return List.copyOf(uniqueIds);
    }

    private record ScrapeOutcome(
            List<ELibrarySearchResponse.ELibraryBook> books,
            long elapsedMs
    ) {
    }

    private record ScrapeTask(
            CompletableFuture<ScrapeOutcome> future,
            long startedAt
    ) {
    }
}
