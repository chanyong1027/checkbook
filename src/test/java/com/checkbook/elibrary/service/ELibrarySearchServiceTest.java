package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.common.ratelimit.ELibraryConcurrencyLimiter;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.RateLimitProperties;
import com.checkbook.elibrary.client.ELibClient;
import com.checkbook.elibrary.client.ELibClientResolver;
import com.checkbook.elibrary.client.ELibraryClientException;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.dto.ELibrarySearchStatus;
import com.checkbook.elibrary.repository.ELibraryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ELibrarySearchServiceTest {

    @Mock
    private ELibraryRepository eLibraryRepository;

    @Mock
    private ELibClientResolver clientResolver;

    @Mock
    private ELibClient mockClient;

    private ExecutorService eLibraryExecutor;
    private final ExternalCallBudget budget = new ExternalCallBudget(
            new RateLimitProperties(true, null, Map.of(), 1, 1, 1), new SimpleMeterRegistry(), Clock.systemUTC());
    private final ELibraryConcurrencyLimiter concurrencyLimiter =
            new ELibraryConcurrencyLimiter(new RateLimitProperties(true, null, Map.of(), 1, 1, 1));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ELibrarySearchService service;

    @BeforeEach
    void setUp() {
        eLibraryExecutor = Executors.newFixedThreadPool(5);
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        service = new ELibrarySearchService(eLibraryRepository, clientResolver, eLibraryExecutor,
                new BookMatcher(normalizer), normalizer, budget, concurrencyLimiter, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        eLibraryExecutor.shutdownNow();
    }

    @Test
    void 도서관별_하루_수집_상한에_닿으면_그_도서관만_DAILY_LIMIT이고_수집하지_않는다() {
        ELibrary first = activeLibrary(1L);
        ELibrary second = activeLibrary(2L);
        when(eLibraryRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of());
        budget.acquireELibrary(1L, false); // 상한 1을 미리 씀

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "1,2");

        assertThat(response.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.DAILY_LIMIT, ELibrarySearchStatus.SUCCESS);
        verify(mockClient, times(1)).search(anyString(), anyString(), anyLong());
    }

    @Test
    void 풀이_거절해_수집하지_못하면_예산을_차감하지_않는다() {
        // 제출 전에 차감하면 풀 포화 거절만으로 자정까지 DAILY_LIMIT이 된다(외부 리뷰 P2)
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ExecutorService rejecting = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        rejecting.shutdown(); // 이후 제출은 전부 거절된다
        ELibrarySearchService saturated = new ELibrarySearchService(eLibraryRepository, clientResolver, rejecting,
                new BookMatcher(normalizer), normalizer, budget, concurrencyLimiter, meterRegistry);

        ELibrarySearchResponse first = saturated.search("자바의 정석", "남궁성", "1");
        assertThat(first.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.FAILED);

        // 풀이 회복된 뒤(정상 서비스) 같은 도서관은 수집할 수 있어야 한다(상한 1)
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of());
        ELibrarySearchResponse second = service.search("자바의 정석", "남궁성", "1");
        assertThat(second.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.SUCCESS);
    }

    @Test
    void robots가_막은_사이트는_더_낮은_하루_상한을_쓴다() {
        ExternalCallBudget robotsBudget = new ExternalCallBudget(
                new RateLimitProperties(true, null, Map.of(), 5, 1, 1), new SimpleMeterRegistry(), Clock.systemUTC());
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ELibrarySearchService robotsService = new ELibrarySearchService(eLibraryRepository, clientResolver,
                eLibraryExecutor, new BookMatcher(normalizer), normalizer, robotsBudget, concurrencyLimiter, meterRegistry);
        ELibrary blocked = activeLibrary(1L);
        ReflectionTestUtils.setField(blocked, "robotsDisallowed", true);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(blocked));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of());

        assertThat(robotsService.search("자바의 정석", "남궁성", "1").results().get(0).status())
                .isEqualTo(ELibrarySearchStatus.SUCCESS);
        assertThat(robotsService.search("자바의 정석", "남궁성", "1").results().get(0).status())
                .isEqualTo(ELibrarySearchStatus.DAILY_LIMIT);
    }

    @Test
    void 같은_사이트에_동시_요청_자리가_없으면_대기_상한까지만_기다리고_시간_초과이며_예산을_쓰지_않는다() throws Exception {
        // 사이트당 동시 요청 상한(결정 2026-10-20). 자리를 못 얻은 요청은 사이트에 가지 않았으니 하루 예산도 그대로여야 한다
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        assertThat(concurrencyLimiter.tryAcquire(1L, 0)).isTrue(); // 다른 요청이 자리(1)를 쥐고 있다
        // 기다린 작업이 끝까지 물러났는지 확인하려고 풀을 따로 둔다 — 확인 없이 자리를 풀면 물러나던 작업이 그 자리로 예산을 쓸 수 있다
        ExecutorService waitingPool = Executors.newFixedThreadPool(1);
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ELibrarySearchService waitingService = new ELibrarySearchService(eLibraryRepository, clientResolver,
                waitingPool, new BookMatcher(normalizer), normalizer, budget, concurrencyLimiter, meterRegistry);
        // 사이트 마감은 넉넉해도(5초) 자리 대기는 상한(여기선 100ms)까지만 — 마감 끝까지 기다리면 수집할 시간이 안 남는다(T4 카드 12)
        ReflectionTestUtils.setField(waitingService, "perLibraryTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(waitingService, "slotWaitTimeoutMs", 100L);

        long started = System.nanoTime();
        ELibrarySearchResponse waited = waitingService.search("자바의 정석", "남궁성", "1");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        waitingPool.shutdown();
        assertThat(waitingPool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        assertThat(waited.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.TIMEOUT);
        assertThat(elapsedMs).as("사이트 마감(5초)이 아니라 자리 대기 상한(100ms) 근처에서 끝난다").isLessThan(2_000);
        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "kyobo", "outcome", "timeout").timer().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("elibrary.slot.wait").tags("result", "timeout").timer().count()).isEqualTo(1);

        // 자리가 나면 같은 사이트를 수집할 수 있다(예산 상한 1이 남아 있어야 SUCCESS)
        concurrencyLimiter.release(1L);
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of());
        assertThat(service.search("자바의 정석", "남궁성", "1").results())
                .extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.SUCCESS);
    }

    // 마감은 풀 큐에서 기다린 시간이 아니라 실제로 시작한 순간부터 잰다(T4 카드 11-1) — 한 번에 많이 고르면
    // 뒤 사이트가 시작도 못 하고 잘리지 않게. 큐 대기는 전체 마감(totalTimeoutMs)이 묶는다
    @Test
    void 사이트_마감은_큐_대기가_아니라_실제로_시작한_때부터_잰다() {
        ExecutorService onePool = Executors.newFixedThreadPool(1);
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ELibrarySearchService oneByOne = new ELibrarySearchService(eLibraryRepository, clientResolver,
                onePool, new BookMatcher(normalizer), normalizer, budget, concurrencyLimiter, meterRegistry);
        ReflectionTestUtils.setField(oneByOne, "perLibraryTimeoutMs", 800L);
        ELibrary first = activeLibrary(1L);
        ELibrary second = activeLibrary(2L);
        when(eLibraryRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Long> remainingAtCall = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 첫 사이트 600ms, 두 번째 300ms — 두 번째는 큐에서 600ms를 기다린다. 제출부터 재면 900ms로 800ms를 넘는다
        when(mockClient.search(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            long deadlineNanos = invocation.getArgument(2);
            remainingAtCall.add(TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            Thread.sleep(calls.getAndIncrement() == 0 ? 600 : 300);
            return List.of();
        });

        ELibrarySearchResponse response = oneByOne.search("자바의 정석", "남궁성", "1,2");
        onePool.shutdownNow();

        assertThat(response.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.SUCCESS, ELibrarySearchStatus.SUCCESS);
        // 두 번째 사이트도 시작할 때 거의 온전한 800ms를 받았다(제출부터 쟀다면 약 200ms)
        assertThat(remainingAtCall.get(1)).isGreaterThanOrEqualTo(700L);
    }

    // 응답(전체 마감)이 이미 나간 뒤에 큐에서 꺼낸 작업은 사이트를 부르지도, 하루 예산을 쓰지도 않는다(1차 리뷰 1).
    // 결과는 이미 TIMEOUT으로 나갔는데 사이트에 부하만 주고 예산(robots 차단 사이트는 하루 200)을 깎으면 안 된다
    @Test
    void 응답이_이미_나간_뒤에_시작한_작업은_수집하지_않고_예산도_쓰지_않는다() throws Exception {
        ExecutorService onePool = Executors.newFixedThreadPool(1);
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ELibrarySearchService oneByOne = new ELibrarySearchService(eLibraryRepository, clientResolver,
                onePool, new BookMatcher(normalizer), normalizer, budget, concurrencyLimiter, meterRegistry);
        ReflectionTestUtils.setField(oneByOne, "totalTimeoutMs", 300L);
        ELibrary slow = activeLibrary(1L);
        ELibrary late = activeLibrary(2L);
        ReflectionTestUtils.setField(late, "baseUrl", "https://late.dkyobobook.co.kr");   // 사이트를 주소로 구분
        when(eLibraryRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(slow, late));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        // 첫 사이트가 600ms를 쥐어, 두 번째는 전체 마감(300ms)이 지난 뒤에야 큐에서 나온다
        when(mockClient.search(eq(slow.getBaseUrl()), anyString(), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(600);
            return List.of();
        });

        ELibrarySearchResponse response = oneByOne.search("자바의 정석", "남궁성", "1,2");
        onePool.shutdown();
        assertThat(onePool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();   // 늦게 시작한 작업까지 다 끝난 뒤 확인

        assertThat(response.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.TIMEOUT, ELibrarySearchStatus.TIMEOUT);
        verify(mockClient, times(1)).search(anyString(), anyString(), anyLong());   // 두 번째 사이트는 부르지 않았다
        // 예산 상한이 1이라, 두 번째 사이트가 예산을 썼다면 다음 검색은 DAILY_LIMIT이 된다
        when(eLibraryRepository.findAllById(List.of(2L))).thenReturn(List.of(late));
        when(mockClient.search(eq(late.getBaseUrl()), anyString(), anyLong())).thenReturn(List.of());
        assertThat(service.search("자바의 정석", "남궁성", "2").results())
                .extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.SUCCESS);
    }

    // 수집기에 사이트 마감(시작 + perLibraryTimeoutMs)을 넘긴다 — 수집기는 갈래·리다이렉트마다 남은 시간만 쓴다
    @Test
    void 수집기는_사이트_마감을_받는다() {
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        java.util.concurrent.atomic.AtomicLong remainingAtCall = new java.util.concurrent.atomic.AtomicLong();
        when(mockClient.search(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            long deadlineNanos = invocation.getArgument(2);
            remainingAtCall.set(TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            return List.of();
        });

        service.search("자바의 정석", "남궁성", "1");

        // 기본 사이트 마감 5초(application.yaml elibrary.per-library-timeout)
        assertThat(remainingAtCall.get()).isBetween(4_000L, 5_000L);
    }

    // 수집기가 사이트 마감에 걸려 끝나도 사용자에게는 지금과 같은 TIMEOUT이다(전에는 대부분 orTimeout이 먼저 걸려 TIMEOUT이었다)
    @Test
    void 수집기의_시간_초과는_TIMEOUT으로_보인다() {
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong()))
                .thenThrow(new ELibraryClientException(ELibraryClientException.Kind.TIMEOUT, "사이트 마감 초과"));

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "1");

        assertThat(response.results()).extracting(ELibrarySearchResponse.ELibraryResult::status)
                .containsExactly(ELibrarySearchStatus.TIMEOUT);
        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "kyobo", "outcome", "timeout").timer().count())
                .isEqualTo(1);
    }

    @Test
    void 수집이_실패해도_동시_요청_자리를_돌려준다() {
        ELibrary first = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(first));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenThrow(new RuntimeException("parse error"));

        service.search("자바의 정석", "남궁성", "1");

        // 자리(1)를 돌려주지 않았으면 0ms 안에 못 얻는다
        assertThat(concurrencyLimiter.tryAcquire(1L, 0)).isTrue();
    }

    @Test
    void 업체와_결과_조합은_수집_전에_0으로_있다() {
        // 구조 변경처럼 드문 결과가 처음 나는 순간에 생기면 increase()가 그 첫 사건을 못 센다
        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "bookcube", "outcome", "structure_changed")
                .timer().count()).isZero();
        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "kyobo", "outcome", "endpoint_not_found")
                .timer().count()).isZero();

        // 자리 대기 결과도 — "자리 없음"은 드물어서 처음 나는 순간을 놓치기 쉽다
        assertThat(meterRegistry.get("elibrary.slot.wait").tags("result", "acquired").timer().count()).isZero();
        assertThat(meterRegistry.get("elibrary.slot.wait").tags("result", "timeout").timer().count()).isZero();
    }

    @Test
    void 수집_결과를_업체와_결과_종류로_센다() {
        // 업체 구조 변경 알림이 이 숫자를 믿는다 — 사이트가 아니라 업체 단위로, 실패 종류를 그대로 남겨야 한다
        when(eLibraryRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(activeLibrary(1L), activeLibrary(2L)));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        AtomicInteger calls = new AtomicInteger();
        // 두 사이트 중 먼저 불린 쪽은 0건, 나머지는 구조 변경(순서는 풀에 달려 있어 어느 쪽이든 각 1건)
        when(mockClient.search(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) {
                throw new ELibraryClientException(ELibraryClientException.Kind.STRUCTURE_CHANGED, "DOM 없음");
            }
            return List.of();
        });

        service.search("자바의 정석", "남궁성", "1,2");

        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "kyobo", "outcome", "empty").timer().count())
                .isEqualTo(1);
        assertThat(meterRegistry.get("elibrary.scrape").tags("vendor", "kyobo", "outcome", "structure_changed").timer().count())
                .isEqualTo(1);
    }

    @Test
    void searchMissingLibraryIdsThrowsException() {
        assertThatThrownBy(() -> service.search("자바의 정석", "남궁성", ""))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_IDS_REQUIRED);
    }

    @Test
    void searchTooManyIdsThrowsException() {
        String ids = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21";

        assertThatThrownBy(() -> service.search("자바의 정석", "남궁성", ids))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.LIBRARY_IDS_LIMIT_EXCEEDED);
    }

    @Test
    void searchDuplicateIdsWithinUniqueLimitDoesNotThrow() {
        ELibrary library = activeLibrary(1L);

        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of());

        ELibrarySearchResponse response = service.search(
                "자바의 정석",
                "남궁성",
                "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1"
        );

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.SUCCESS);
    }

    @Test
    void searchStaleIdRecordedAsFailure() {
        when(eLibraryRepository.findAllById(List.of(999L))).thenReturn(List.of());

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "999");

        assertThat(response.results()).isEmpty();
        assertThat(response.metadata().failures()).hasSize(1);
        assertThat(response.metadata().failures().get(0).libraryId()).isEqualTo(999L);
    }

    @Test
    void searchActiveLibraryReturnsResult() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong()))
                .thenReturn(List.of(new ELibrarySearchResponse.ELibraryBook(
                        "자바의 정석", "남궁성", "도우출판", null, true, null
                )));

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "3");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.SUCCESS);
        assertThat(response.results().get(0).books()).hasSize(1);
    }

    @Test
    void searchInactiveAndLoginRequiredRecordedAsFailures() {
        ELibrary inactive = activeLibrary(3L);
        ReflectionTestUtils.setField(inactive, "status", ELibraryStatus.INACTIVE);
        ELibrary loginRequired = activeLibrary(5L);
        ReflectionTestUtils.setField(loginRequired, "loginRequired", true);

        when(eLibraryRepository.findAllById(List.of(3L, 5L))).thenReturn(List.of(inactive, loginRequired));

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "3,5");

        assertThat(response.results()).isEmpty();
        assertThat(response.metadata().failures()).hasSize(2);
        assertThat(response.metadata().failures())
                .extracting(ELibrarySearchResponse.ELibraryFailureDetail::libraryId)
                .containsExactly(3L, 5L);
    }

    @Test
    void searchClientThrowsMarksLibraryAsFailed() {
        ELibrary library = activeLibrary(3L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(eq(library.getBaseUrl()), eq("자바의 정석"), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(25);
            throw new RuntimeException("parse error");
        });

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "3");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.FAILED);
        assertThat(response.results().get(0).books()).isEmpty();
        assertThat(response.results().get(0).elapsedMs()).isGreaterThan(0L);
    }

    @Test
    void searchTotalTimeoutUsesActualElapsedMs() {
        ELibrary library = activeLibrary(3L);
        ReflectionTestUtils.setField(service, "totalTimeoutMs", 20L);
        ReflectionTestUtils.setField(service, "perLibraryTimeoutMs", 200L);

        when(eLibraryRepository.findAllById(List.of(3L))).thenReturn(List.of(library));
        when(clientResolver.resolve(VendorType.KYOBO)).thenReturn(mockClient);
        when(mockClient.search(eq(library.getBaseUrl()), eq("자바의 정석"), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(80);
            return List.of();
        });

        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성", "3");

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).status()).isEqualTo(ELibrarySearchStatus.TIMEOUT);
        assertThat(response.results().get(0).elapsedMs()).isGreaterThanOrEqualTo(20L);
    }

    @Test
    void unrelatedBooksAreFilteredOut() {
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(library.getVendorType())).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of(
                new ELibrarySearchResponse.ELibraryBook("인간실격", "다자이 오사무", null, null, true, null),
                new ELibrarySearchResponse.ELibraryBook("인간실격 따라쓰기", "정리남", null, null, true, null)
        ));

        ELibrarySearchResponse res = service.search("인간실격", "다자이 오사무", "1");

        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).books()).hasSize(1);
        assertThat(res.results().get(0).books().get(0).title()).isEqualTo("인간실격");
    }

    @Test
    void emptyAuthorOnVendor_passesViaTitleOnly() {
        ELibrary library = activeLibrary(1L);
        when(eLibraryRepository.findAllById(List.of(1L))).thenReturn(List.of(library));
        when(clientResolver.resolve(library.getVendorType())).thenReturn(mockClient);
        when(mockClient.search(anyString(), anyString(), anyLong())).thenReturn(List.of(
                new ELibrarySearchResponse.ELibraryBook("인간실격", null, null, null, true, null)
        ));

        ELibrarySearchResponse res = service.search("인간실격", "다자이 오사무", "1");

        assertThat(res.results().get(0).books()).hasSize(1);
    }

    @Test
    void titleMissing_throws() {
        assertThatThrownBy(() -> service.search("", "다자이 오사무", "1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_SEARCH_KEYWORD);
    }

    private ELibrary activeLibrary(Long id) {
        ELibrary library = ELibrary.builder()
                .name("테스트도서관")
                .baseUrl("https://test.dkyobobook.co.kr")
                .vendorType(VendorType.KYOBO)
                .status(ELibraryStatus.ACTIVE)
                .loginRequired(false)
                .build();
        ReflectionTestUtils.setField(library, "id", id);
        return library;
    }
}
