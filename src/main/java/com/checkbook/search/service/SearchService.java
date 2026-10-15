package com.checkbook.search.service;

import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.concurrent.AsyncSubmit;
import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.dto.PublicLibraryInfo;
import com.checkbook.publiclibrary.service.PublicLibraryAvailabilityService;
import com.checkbook.search.dto.BookHint;
import com.checkbook.search.dto.IdentificationResult;
import com.checkbook.search.dto.IdentificationResult.Source;
import com.checkbook.search.dto.IdentifiedBook;
import com.checkbook.search.dto.MillieAvailability;
import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionReason;
import com.checkbook.search.dto.SearchSectionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 통합 검색. ISBN만 받는다(키워드는 400).
 * <ol>
 *   <li>식별(순차): YES24 1회, 실패·미발견이면 프런트 힌트. 비표준 코드는 YES24·도서관·중고를 부르지 않는다</li>
 *   <li>fan-out(전체 데드라인): 도서관(위치 있을 때), 중고, 밀리(책 정보가 있을 때)</li>
 * </ol>
 * 섹션 상태와 failures는 데드라인 뒤 각 작업의 상태를 한 번씩 읽어 함께 만든다. 그래서 FAILED 섹션에는
 * failures가 정확히 하나, SUCCESS·SKIPPED에는 없다(계약 2절). 늦게 끝난 작업이 목록을 바꾸지 못한다.
 */
@Slf4j
@Service
public class SearchService {

    private static final String TIMEOUT_MESSAGE = "타임아웃";
    private static final String POOL_SATURATED_MESSAGE = "검색 풀 포화";

    private final BookIdentificationService identificationService;
    private final AladinBookService aladinBookService;
    private final MillieBookService millieBookService;
    private final PublicLibraryAvailabilityService publicLibraryAvailabilityService;
    private final ExecutorService searchExecutor;

    @Value("${search.total-deadline:2800}")
    private long totalDeadlineMs = 2800;

    public SearchService(
            BookIdentificationService identificationService,
            AladinBookService aladinBookService,
            MillieBookService millieBookService,
            PublicLibraryAvailabilityService publicLibraryAvailabilityService,
            @Qualifier("searchExecutor") ExecutorService searchExecutor
    ) {
        this.identificationService = identificationService;
        this.aladinBookService = aladinBookService;
        this.millieBookService = millieBookService;
        this.publicLibraryAvailabilityService = publicLibraryAvailabilityService;
        this.searchExecutor = searchExecutor;
    }

    public SearchResponse search(String q, Double lat, Double lon, BookHint hint) {
        validateLocation(lat, lon);

        InputNormalizer.NormalizedQuery normalized = InputNormalizer.normalize(q);
        if (normalized.type() == InputNormalizer.QueryType.KEYWORD) {
            throw new BusinessException(ErrorCode.KEYWORD_NOT_SUPPORTED);
        }
        boolean standard = normalized.type() == InputNormalizer.QueryType.ISBN;
        String code = normalized.value();
        boolean hasLocation = lat != null && lon != null;

        IdentificationResult identification = standard
                ? identificationService.identify(code, hint)
                : identificationService.identifyWithoutLookup(code, hint);
        log.info("통합 검색: code={}, standard={}, source={}, yes24={}",
                code, standard, identification.source(), identification.yes24());

        // ---- fan-out (ISBN 기반 호출은 표준 ISBN일 때만) ----
        CompletableFuture<AladinUsedBookResult> usedFuture = standard
                ? AsyncSubmit.submitSafely(() -> aladinBookService.getUsedBooks(code), searchExecutor)
                : null;
        CompletableFuture<PublicLibraryAvailabilityPage> publicFuture = standard && hasLocation
                ? AsyncSubmit.submitSafely(() -> publicLibraryAvailabilityService.fetch(code, lat, lon, 0), searchExecutor)
                : null;
        IdentifiedBook book = identification.book();
        CompletableFuture<MillieAvailability> millieFuture = identification.source() != Source.NONE
                ? AsyncSubmit.submitSafely(
                        () -> millieBookService.findAvailability(book, identification.hintBook()), searchExecutor)
                : null;

        awaitDeadline(usedFuture, publicFuture, millieFuture);

        // ---- 섹션별 결과: 각 작업 상태를 한 번만 읽는다 ----
        Section<PublicLibraryAvailabilityPage> library = !standard
                ? Section.skipped(SearchSectionReason.NON_STANDARD_ISBN)
                : !hasLocation ? Section.skipped(SearchSectionReason.NO_LOCATION) : Section.of(publicFuture);
        Section<AladinUsedBookResult> used = standard
                ? Section.of(usedFuture)
                : Section.skipped(SearchSectionReason.NON_STANDARD_ISBN);
        Section<MillieAvailability> millie = millieFuture != null
                ? Section.of(millieFuture)
                : Section.skipped(SearchSectionReason.NO_METADATA);
        Section<SearchResponse.NewBookInfo> newBook = newBookSection(identification);

        List<SearchResponse.SectionStatusDetail> statuses = new ArrayList<>();
        List<SearchResponse.FailureDetail> failures = new ArrayList<>();
        library.appendTo(SearchSection.PUBLIC_LIBRARY, statuses, failures);
        used.appendTo(SearchSection.USED_BOOK, statuses, failures);
        newBook.appendTo(SearchSection.NEW_BOOK, statuses, failures);
        millie.appendTo(SearchSection.SUBSCRIPTION, statuses, failures);

        PublicLibraryAvailabilityPage publicPage = library.value();
        List<PublicLibraryInfo> publicResults = publicPage != null ? publicPage.libraries() : List.of();
        AladinUsedBookResult usedResult = used.value();
        MillieAvailability millieResult = millie.value() != null ? millie.value() : MillieAvailability.unavailable();

        return new SearchResponse(
                new SearchResponse.BookInfo(book.title(), book.author(), book.isbn13(), book.publisher(), book.coverUrl()),
                publicResults,
                usedResult == null ? null : new SearchResponse.UsedBookInfo(
                        usedResult.userUsedPrice(),
                        usedResult.aladinUsedPrice(),
                        usedResult.spaceUsedPrice(),
                        usedResult.userUsedUrl(),
                        usedResult.aladinUsedUrl(),
                        usedResult.spaceUsedUrl()),
                newBook.value(),
                new SearchResponse.SubscriptionInfo(millieResult),
                new SearchResponse.SearchMetadata(
                        LocalDateTime.now(), statuses, failures,
                        publicPage != null ? publicPage.total() : null,
                        publicPage != null && publicPage.hasMoreLibraries(),
                        publicPage != null ? publicPage.nextOffset() : null,
                        new SearchResponse.IdentificationInfo(identification.source(), identification.yes24()))
        );
    }

    // 새 책은 YES24 결과로만 정한다(힌트 무관). 한도 거절은 우리가 호출하지 않았으면 SKIPPED, YES24가 거절했으면 FAILED
    private Section<SearchResponse.NewBookInfo> newBookSection(IdentificationResult identification) {
        boolean sent = identification.yes24Sent();
        return switch (identification.yes24()) {
            case FOUND -> identification.price() != null && identification.productUrl() != null
                    ? Section.success(new SearchResponse.NewBookInfo(identification.price(), identification.productUrl()), null)
                    : Section.success(null, SearchSectionReason.PRICE_UNAVAILABLE);
            case NOT_FOUND -> Section.success(null, SearchSectionReason.NOT_FOUND);
            case TIMEOUT -> Section.failed(SearchSectionReason.TIMEOUT, TIMEOUT_MESSAGE);
            case FAILED -> Section.failed(SearchSectionReason.UNAVAILABLE, "YES24 오류");
            case RATE_LIMITED -> sent
                    ? Section.failed(SearchSectionReason.RATE_LIMITED, "YES24 호출 한도(초당)")
                    : Section.skipped(SearchSectionReason.RATE_LIMITED);
            case DAILY_LIMIT -> sent
                    ? Section.failed(SearchSectionReason.DAILY_LIMIT, "YES24 호출 한도(일일)")
                    : Section.skipped(SearchSectionReason.DAILY_LIMIT);
            case SKIPPED -> Section.skipped(SearchSectionReason.NON_STANDARD_ISBN);
        };
    }

    private void awaitDeadline(CompletableFuture<?>... futures) {
        CompletableFuture<?>[] started = Arrays.stream(futures)
                .filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new);
        if (started.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(started).get(totalDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("통합 검색 전체 데드라인 {}ms 초과", totalDeadlineMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // 개별 실패는 섹션별로 읽는다
        }
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

    /**
     * 한 섹션의 결과. 상태·사유·값·실패 문구를 함께 들고 있다가 응답에 한 번에 붙인다.
     */
    private record Section<T>(SearchSectionStatus status, SearchSectionReason reason, T value, String failureMessage) {

        static <T> Section<T> success(T value, SearchSectionReason reason) {
            return new Section<>(SearchSectionStatus.SUCCESS, reason, value, null);
        }

        static <T> Section<T> skipped(SearchSectionReason reason) {
            return new Section<>(SearchSectionStatus.SKIPPED, reason, null, null);
        }

        static <T> Section<T> failed(SearchSectionReason reason, String failureMessage) {
            return new Section<>(SearchSectionStatus.FAILED, reason, null, failureMessage);
        }

        // 데드라인 시점의 작업 상태를 한 번만 읽는다. 끝나지 않았으면 타임아웃
        static <T> Section<T> of(CompletableFuture<T> future) {
            return switch (future.state()) {
                case SUCCESS -> success(future.resultNow(), null);
                case FAILED -> {
                    Throwable cause = unwrap(future.exceptionNow());
                    yield isTimeout(cause)
                            ? failed(SearchSectionReason.TIMEOUT, TIMEOUT_MESSAGE)
                            : failed(SearchSectionReason.UNAVAILABLE, failureMessage(cause));
                }
                case RUNNING, CANCELLED -> failed(SearchSectionReason.TIMEOUT, TIMEOUT_MESSAGE);
            };
        }

        void appendTo(SearchSection section,
                      List<SearchResponse.SectionStatusDetail> statuses,
                      List<SearchResponse.FailureDetail> failures) {
            statuses.add(new SearchResponse.SectionStatusDetail(section, status, reason));
            if (status == SearchSectionStatus.FAILED) {
                failures.add(new SearchResponse.FailureDetail(section, failureMessage));
            }
        }

        private static Throwable unwrap(Throwable e) {
            Throwable t = e;
            while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
                t = t.getCause();
            }
            return t;
        }

        private static boolean isTimeout(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException || t instanceof TimeoutException) {
                    return true;
                }
            }
            return false;
        }

        private static String failureMessage(Throwable cause) {
            if (cause instanceof RejectedExecutionException) {
                // TPE 내부 문자열(스레드 정보 포함 원문)을 클라이언트에 노출하지 않고,
                // fault 분석에서 타임아웃("타임아웃")과 거절을 구분 가능하게 정규화
                return POOL_SATURATED_MESSAGE;
            }
            return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        }
    }
}
