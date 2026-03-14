package com.checkbook.search.service;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.naver.NaverShoppingClient;
import com.checkbook.client.naver.dto.NaverShoppingResult;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.DistanceCalculator;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class SearchService {

    private final AladinClient aladinClient;
    private final NaverShoppingClient naverClient;
    private final DatanaruClient datanaruClient;
    private final PublicLibraryRepository publicLibraryRepository;
    private final ExecutorService searchExecutor;
    private final ExecutorService publicLibraryExecutor;

    @Value("${search.total-deadline:2800}")
    private long totalDeadlineMs = 2800;

    @Value("${search.public-library-top-n:20}")
    private int publicLibraryTopN = 20;

    @Value("${search.public-library-fanout-timeout:2200}")
    private long publicLibraryFanoutTimeoutMs = 2200;

    public SearchService(
            AladinClient aladinClient,
            NaverShoppingClient naverClient,
            DatanaruClient datanaruClient,
            PublicLibraryRepository publicLibraryRepository,
            @Qualifier("searchExecutor") ExecutorService searchExecutor,
            @Qualifier("publicLibraryExecutor") ExecutorService publicLibraryExecutor
    ) {
        this.aladinClient = aladinClient;
        this.naverClient = naverClient;
        this.datanaruClient = datanaruClient;
        this.publicLibraryRepository = publicLibraryRepository;
        this.searchExecutor = searchExecutor;
        this.publicLibraryExecutor = publicLibraryExecutor;
    }

    public SearchResponse search(String q, Double lat, Double lon) {
        validateLocation(lat, lon);

        InputNormalizer.NormalizedQuery normalized = InputNormalizer.normalize(q);
        log.info("통합 검색: query={}, type={}", normalized.value(), normalized.type());

        Optional<AladinSearchResult> identifiedBook = identify(normalized);
        String isbn13 = identifiedBook.map(AladinSearchResult::isbn13)
                .orElse(normalized.type() == InputNormalizer.QueryType.ISBN ? normalized.value() : null);

        if (isbn13 == null) {
            log.info("isbn13 null - 키워드 입력 + 알라딘 실패: 모든 섹션 SKIPPED");
            return buildSkippedResponse(identifiedBook.orElse(null));
        }

        List<SearchResponse.FailureDetail> failures = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<AladinUsedBookResult> usedFuture = CompletableFuture
                .supplyAsync(() -> aladinClient.getUsedBooks(isbn13), searchExecutor)
                .exceptionally(exception -> {
                    failures.add(new SearchResponse.FailureDetail(
                            SearchSection.USED_BOOK,
                            failureReason(exception)));
                    return null;
                });

        CompletableFuture<List<NaverShoppingResult>> newFuture = CompletableFuture
                .supplyAsync(() -> naverClient.searchNewBooks(isbn13), searchExecutor)
                .exceptionally(exception -> {
                    failures.add(new SearchResponse.FailureDetail(
                            SearchSection.NEW_BOOK,
                            failureReason(exception)));
                    return List.of();
                });

        CompletableFuture<List<SearchResponse.PublicLibraryInfo>> publicFuture;
        if (lat != null && lon != null) {
            publicFuture = CompletableFuture
                    .supplyAsync(() -> fetchPublicLibraries(isbn13, lat, lon), searchExecutor)
                    .exceptionally(exception -> {
                        failures.add(new SearchResponse.FailureDetail(
                                SearchSection.PUBLIC_LIBRARY,
                                failureReason(exception)));
                        return List.of();
                    });
        } else {
            publicFuture = CompletableFuture.completedFuture(List.of());
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(usedFuture, newFuture, publicFuture);
        try {
            allFutures.get(totalDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("통합 검색 전체 데드라인 {}ms 초과", totalDeadlineMs);
        } catch (Exception e) {
            log.warn("통합 검색 대기 중 오류", e);
        }

        AladinUsedBookResult usedResult =
                usedFuture.isDone() && !usedFuture.isCompletedExceptionally() ? usedFuture.join() : null;
        List<NaverShoppingResult> newResults =
                newFuture.isDone() && !newFuture.isCompletedExceptionally() ? newFuture.join() : List.of();
        List<SearchResponse.PublicLibraryInfo> publicResults =
                publicFuture.isDone() && !publicFuture.isCompletedExceptionally() ? publicFuture.join() : List.of();

        List<SearchResponse.SectionStatusDetail> statuses =
                buildStatuses(usedFuture, newFuture, publicFuture, lat, lon, failures);

        SearchResponse.BookInfo bookInfo = identifiedBook
                .map(book -> new SearchResponse.BookInfo(
                        book.title(), book.author(), book.isbn13(), book.publisher(), book.coverUrl()))
                .orElse(new SearchResponse.BookInfo(null, null, isbn13, null, null));

        SearchResponse.UsedBookInfo usedBookInfo = usedResult == null
                ? null
                : new SearchResponse.UsedBookInfo(
                usedResult.userUsedPrice(),
                usedResult.aladinUsedPrice(),
                usedResult.spaceUsedPrice(),
                usedResult.userUsedUrl(),
                usedResult.aladinUsedUrl(),
                usedResult.spaceUsedUrl());

        List<SearchResponse.NewBookInfo> newBookInfos = newResults.stream()
                .sorted(Comparator.comparingInt(NaverShoppingResult::price))
                .map(book -> new SearchResponse.NewBookInfo(
                        book.mallName(),
                        book.price(),
                        book.productUrl()))
                .toList();

        return new SearchResponse(
                bookInfo,
                publicResults,
                usedBookInfo,
                newBookInfos,
                new SearchResponse.SearchMetadata(LocalDateTime.now(), statuses, List.copyOf(failures))
        );
    }

    private Optional<AladinSearchResult> identify(InputNormalizer.NormalizedQuery normalized) {
        if (normalized.type() == InputNormalizer.QueryType.ISBN) {
            return aladinClient.lookupBook(normalized.value());
        }
        return aladinClient.searchBook(normalized.value());
    }

    private List<SearchResponse.PublicLibraryInfo> fetchPublicLibraries(String isbn13, double lat, double lon) {
        List<PublicLibrary> nearbyLibraries = publicLibraryRepository.findNearest(lat, lon, publicLibraryTopN);

        List<CompletableFuture<SearchResponse.PublicLibraryInfo>> futures = nearbyLibraries.stream()
                .map(library -> CompletableFuture.supplyAsync(() -> {
                    DatanaruBookExistResult existResult = datanaruClient.bookExist(isbn13, library.getLibCode());
                    double distance = Math.round(
                            DistanceCalculator.km(lat, lon, library.getLat(), library.getLon()) * 10.0
                    ) / 10.0;

                    return new SearchResponse.PublicLibraryInfo(
                            library.getName(),
                            existResult.hasBook(),
                            existResult.loanAvailable(),
                            library.getAddress(),
                            library.getLat(),
                            library.getLon(),
                            distance,
                            library.getHomepage()
                    );
                }, publicLibraryExecutor).exceptionally(exception -> {
                    log.warn("bookExist 호출 실패: {} - 건너뜀", library.getName(), exception);
                    return null;
                }))
                .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        try {
            all.get(publicLibraryFanoutTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("공공도서관 bookExist fan-out 타임아웃 {}ms", publicLibraryFanoutTimeoutMs);
        } catch (Exception e) {
            log.warn("공공도서관 bookExist fan-out 대기 중 오류", e);
        }

        return futures.stream()
                .filter(CompletableFuture::isDone)
                .map(future -> future.getNow(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(SearchResponse.PublicLibraryInfo::distance))
                .toList();
    }

    private void validateLocation(Double lat, Double lon) {
        boolean latPresent = lat != null;
        boolean lonPresent = lon != null;

        if (latPresent != lonPresent) {
            throw new BusinessException(ErrorCode.INVALID_LOCATION);
        }

        if (latPresent && (lat < -90 || lat > 90 || lon < -180 || lon > 180)) {
            throw new BusinessException(ErrorCode.INVALID_LOCATION);
        }
    }

    private SearchResponse buildSkippedResponse(AladinSearchResult book) {
        SearchResponse.BookInfo bookInfo = book == null
                ? new SearchResponse.BookInfo(null, null, null, null, null)
                : new SearchResponse.BookInfo(
                book.title(),
                book.author(),
                book.isbn13(),
                book.publisher(),
                book.coverUrl());

        List<SearchResponse.SectionStatusDetail> statuses = List.of(
                new SearchResponse.SectionStatusDetail(SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED),
                new SearchResponse.SectionStatusDetail(SearchSection.USED_BOOK, SearchSectionStatus.SKIPPED),
                new SearchResponse.SectionStatusDetail(SearchSection.NEW_BOOK, SearchSectionStatus.SKIPPED)
        );

        return new SearchResponse(
                bookInfo,
                List.of(),
                null,
                List.of(),
                new SearchResponse.SearchMetadata(LocalDateTime.now(), statuses, List.of())
        );
    }

    private List<SearchResponse.SectionStatusDetail> buildStatuses(
            CompletableFuture<?> usedFuture,
            CompletableFuture<?> newFuture,
            CompletableFuture<?> publicFuture,
            Double lat,
            Double lon,
            List<SearchResponse.FailureDetail> failures
    ) {
        List<SearchResponse.SectionStatusDetail> statuses = new ArrayList<>();

        if (lat == null || lon == null) {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SKIPPED));
        } else if (!publicFuture.isDone()
                || publicFuture.isCompletedExceptionally()
                || hasFailure(failures, SearchSection.PUBLIC_LIBRARY)) {
            addTimeoutFailureIfAbsent(failures, SearchSection.PUBLIC_LIBRARY);
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.FAILED));
        } else {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.PUBLIC_LIBRARY, SearchSectionStatus.SUCCESS));
        }

        if (!usedFuture.isDone()
                || usedFuture.isCompletedExceptionally()
                || hasFailure(failures, SearchSection.USED_BOOK)) {
            addTimeoutFailureIfAbsent(failures, SearchSection.USED_BOOK);
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.USED_BOOK, SearchSectionStatus.FAILED));
        } else {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.USED_BOOK, SearchSectionStatus.SUCCESS));
        }

        if (!newFuture.isDone()
                || newFuture.isCompletedExceptionally()
                || hasFailure(failures, SearchSection.NEW_BOOK)) {
            addTimeoutFailureIfAbsent(failures, SearchSection.NEW_BOOK);
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.NEW_BOOK, SearchSectionStatus.FAILED));
        } else {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.NEW_BOOK, SearchSectionStatus.SUCCESS));
        }

        return statuses;
    }

    private boolean hasFailure(List<SearchResponse.FailureDetail> failures, SearchSection section) {
        return failures.stream().anyMatch(failure -> failure.section() == section);
    }

    private void addTimeoutFailureIfAbsent(List<SearchResponse.FailureDetail> failures, SearchSection section) {
        if (!hasFailure(failures, section)) {
            failures.add(new SearchResponse.FailureDetail(section, "타임아웃"));
        }
    }

    private String failureReason(Throwable exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
