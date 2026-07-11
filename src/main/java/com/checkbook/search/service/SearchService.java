package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.concurrent.AsyncSubmit;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class SearchService {

    private final AladinBookService aladinBookService;
    private final MillieBookService millieBookService;
    private final PublicLibraryAvailabilityService publicLibraryAvailabilityService;
    private final ExecutorService searchExecutor;

    @Value("${search.total-deadline:2800}")
    private long totalDeadlineMs = 2800;

    public SearchService(
            AladinBookService aladinBookService,
            MillieBookService millieBookService,
            PublicLibraryAvailabilityService publicLibraryAvailabilityService,
            @Qualifier("searchExecutor") ExecutorService searchExecutor
    ) {
        this.aladinBookService = aladinBookService;
        this.millieBookService = millieBookService;
        this.publicLibraryAvailabilityService = publicLibraryAvailabilityService;
        this.searchExecutor = searchExecutor;
    }

    public SearchResponse search(String q, Double lat, Double lon) {
        validateLocation(lat, lon);

        InputNormalizer.NormalizedQuery normalized = InputNormalizer.normalize(q);
        log.info("통합 검색: query={}, type={}", normalized.value(), normalized.type());

        Optional<AladinSearchResult> identifiedBook = aladinBookService.identify(normalized);
        String isbn13 = identifiedBook.map(AladinSearchResult::isbn13)
                .orElse(normalized.type() == InputNormalizer.QueryType.ISBN ? normalized.value() : null);

        if (isbn13 == null) {
            log.info("isbn13 null - 키워드 입력 + 알라딘 실패: 모든 섹션 SKIPPED");
            return buildSkippedResponse(identifiedBook.orElse(null), identifiedBook.isPresent());
        }

        List<SearchResponse.FailureDetail> failures = new CopyOnWriteArrayList<>();

        CompletableFuture<AladinUsedBookResult> usedFuture =
                AsyncSubmit.submitSafely(() -> aladinBookService.getUsedBooks(isbn13), searchExecutor)
                .exceptionally(exception -> {
                    failures.add(new SearchResponse.FailureDetail(
                            SearchSection.USED_BOOK,
                            failureReason(exception)));
                    return null;
                });


        CompletableFuture<PublicLibraryAvailabilityPage> publicFuture;
        if (lat != null && lon != null) {
            publicFuture = AsyncSubmit.submitSafely(
                            () -> publicLibraryAvailabilityService.fetch(isbn13, lat, lon, 0), searchExecutor)
                    .exceptionally(exception -> {
                        failures.add(new SearchResponse.FailureDetail(
                                SearchSection.PUBLIC_LIBRARY,
                                failureReason(exception)));
                        return null;
                    });
        } else {
            publicFuture = CompletableFuture.completedFuture(null);
        }

        CompletableFuture<MillieAvailability> millieFuture = identifiedBook
                .map(book -> AsyncSubmit.submitSafely(() -> millieBookService.findAvailability(book), searchExecutor)
                        .exceptionally(exception -> {
                            failures.add(new SearchResponse.FailureDetail(
                                    SearchSection.SUBSCRIPTION,
                                    failureReason(exception)));
                            return MillieAvailability.unavailable();
                        }))
                .orElseGet(() -> CompletableFuture.completedFuture(MillieAvailability.unavailable()));

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(usedFuture, publicFuture, millieFuture);
        try {
            allFutures.get(totalDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("통합 검색 전체 데드라인 {}ms 초과", totalDeadlineMs);
        } catch (Exception e) {
            log.warn("통합 검색 대기 중 오류", e);
        }

        AladinUsedBookResult usedResult =
                usedFuture.isDone() && !usedFuture.isCompletedExceptionally() ? usedFuture.join() : null;
        PublicLibraryAvailabilityPage publicPage =
                publicFuture.isDone() && !publicFuture.isCompletedExceptionally() ? publicFuture.join() : null;
        List<PublicLibraryInfo> publicResults =
                publicPage != null ? publicPage.libraries() : List.of();
        Integer publicLibraryTotal = publicPage != null ? publicPage.total() : null;
        boolean publicLibraryHasMore = publicPage != null && publicPage.hasMoreLibraries();
        Integer publicLibraryNextOffset = publicPage != null ? publicPage.nextOffset() : null;
        MillieAvailability millieResult =
                millieFuture.isDone() && !millieFuture.isCompletedExceptionally() ? millieFuture.join() : MillieAvailability.unavailable();

        List<SearchResponse.SectionStatusDetail> statuses =
                buildStatuses(usedFuture, publicFuture, millieFuture, lat, lon, failures, identifiedBook.isPresent());

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

        SearchResponse.NewBookInfo newBookInfo = identifiedBook
                .filter(book -> book.priceSales() != null && book.priceSales() > 0)
                .map(book -> new SearchResponse.NewBookInfo(
                        book.priceSales(),
                        "https://www.aladin.co.kr/shop/wproduct.aspx?ISBN=" + book.isbn13()))
                .orElse(null);

        return new SearchResponse(
                bookInfo,
                publicResults,
                usedBookInfo,
                newBookInfo,
                new SearchResponse.SubscriptionInfo(millieResult),
                new SearchResponse.SearchMetadata(
                        LocalDateTime.now(), statuses, List.copyOf(failures),
                        publicLibraryTotal, publicLibraryHasMore, publicLibraryNextOffset)
        );
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

    private SearchResponse buildSkippedResponse(AladinSearchResult book, boolean aladinIdentified) {
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
                new SearchResponse.SectionStatusDetail(SearchSection.NEW_BOOK,
                        aladinIdentified ? SearchSectionStatus.SUCCESS : SearchSectionStatus.FAILED),
                new SearchResponse.SectionStatusDetail(SearchSection.SUBSCRIPTION, SearchSectionStatus.SKIPPED)
        );

        return new SearchResponse(
                bookInfo,
                List.of(),
                null,
                null,
                new SearchResponse.SubscriptionInfo(MillieAvailability.unavailable()),
                new SearchResponse.SearchMetadata(LocalDateTime.now(), statuses, List.of(), null, false, null)
        );
    }

    private List<SearchResponse.SectionStatusDetail> buildStatuses(
            CompletableFuture<?> usedFuture,
            CompletableFuture<?> publicFuture,
            CompletableFuture<?> millieFuture,
            Double lat,
            Double lon,
            List<SearchResponse.FailureDetail> failures,
            boolean aladinIdentified
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

        // NEW_BOOK: 알라딘 식별 성공 시 SUCCESS (newBook이 null이면 가격 미제공), 실패 시 FAILED
        statuses.add(new SearchResponse.SectionStatusDetail(
                SearchSection.NEW_BOOK,
                aladinIdentified ? SearchSectionStatus.SUCCESS : SearchSectionStatus.FAILED));

        // SUBSCRIPTION: 알라딘 도서 메타데이터 없음(`identifiedBook.isEmpty()`) → SKIPPED.
        // 호출 실패/타임아웃 시 FAILED.
        // 정상 완료 시 (available true 또는 false 모두) SUCCESS.
        if (!aladinIdentified) {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.SUBSCRIPTION, SearchSectionStatus.SKIPPED));
        } else if (!millieFuture.isDone()
                || millieFuture.isCompletedExceptionally()
                || hasFailure(failures, SearchSection.SUBSCRIPTION)) {
            addTimeoutFailureIfAbsent(failures, SearchSection.SUBSCRIPTION);
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.SUBSCRIPTION, SearchSectionStatus.FAILED));
        } else {
            statuses.add(new SearchResponse.SectionStatusDetail(
                    SearchSection.SUBSCRIPTION, SearchSectionStatus.SUCCESS));
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
        if (cause instanceof RejectedExecutionException) {
            // TPE 내부 문자열(스레드 정보 포함 원문)을 클라이언트에 노출하지 않고,
            // fault 분석에서 타임아웃("타임아웃")과 거절을 구분 가능하게 정규화
            return "검색 풀 포화";
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
