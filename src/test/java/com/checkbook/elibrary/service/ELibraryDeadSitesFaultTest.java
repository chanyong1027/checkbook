package com.checkbook.elibrary.service;

import com.checkbook.common.matcher.BookMatcher;
import com.checkbook.common.matcher.BookMetadataNormalizer;
import com.checkbook.common.ratelimit.ELibraryConcurrencyLimiter;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.RateLimitProperties;
import com.checkbook.elibrary.client.ELibClientResolver;
import com.checkbook.elibrary.client.KyoboELibClient;
import com.checkbook.elibrary.domain.ELibrary;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;
import com.checkbook.elibrary.dto.ELibrarySearchStatus;
import com.checkbook.elibrary.repository.ELibraryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 응답하지 않는 전자도서관이 섞인 검색을 진짜 교보 클라이언트 + 가짜 사이트로 재현한다(T4 결정 카드 11·14).
 * 죽은 사이트를 풀 스레드 수(5)보다 많이 둔다 — 그래야 '죽은 사이트가 스레드를 다 쥐어 뒤의 정상 사이트가 밀리는' 상황이 된다.
 * <p>
 * 같은 상황 실측(10-22, 사본): 이 PR 전 코드(마감 15초를 제출 시각부터, Jsoup 요청마다 15초 고정) — 정상 14곳 성공,
 * 죽은 6곳은 FAILED 5 + TIMEOUT 1로 섞임(Jsoup이 먼저 끊으면 FAILED, orTimeout이 먼저면 TIMEOUT), 응답 15.05초.
 * 이 PR(사이트 5초) — 정상 14곳 성공, 죽은 6곳 모두 TIMEOUT, 응답 5.09초. 정상 사이트가 전에도 살아남은 건 Jsoup이 응답 대기를
 * 시간 제한의 절반(7.5초)으로 두어 죽은 사이트가 15초 전에 스레드를 놓았기 때문이다. 여기서는 시험을 빠르게 하려고 마감을 1초로 줄인다.
 */
class ELibraryDeadSitesFaultTest {

    private static final long SITE_DEADLINE_MS = 1_000;
    private static final int DEAD = 6;
    private static final int TOTAL = 20;

    private MockWebServer server;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().startsWith("/dead")) {
                    return new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE);   // 연결은 받고 답하지 않음
                }
                return new MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                        .setBody("<html><body><ul class=\"book_resultList\"></ul></body></html>");
            }
        });
        server.start();
        // 운영과 같은 풀 모양(application.yaml elibrary.thread-pool-size 5, 큐 25)
        pool = new ThreadPoolExecutor(5, 5, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(25));
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        server.shutdown();
    }

    @Test
    void 죽은_사이트가_풀을_다_쥐어도_정상_사이트는_결과를_받는다() {
        List<ELibrary> libraries = new ArrayList<>();
        for (long id = 1; id <= TOTAL; id++) {
            // 앞쪽 6곳이 죽은 사이트 — 먼저 제출돼 스레드 5개를 다 쥔다
            libraries.add(library(id, server.url((id <= DEAD ? "/dead" : "/ok") + id).toString().replaceAll("/$", "")));
        }
        List<Long> ids = LongStream.rangeClosed(1, TOTAL).boxed().toList();
        ELibraryRepository repository = mock(ELibraryRepository.class);
        when(repository.findAllById(ids)).thenReturn(libraries);
        ELibClientResolver resolver = mock(ELibClientResolver.class);
        when(resolver.resolve(VendorType.KYOBO)).thenReturn(new KyoboELibClient());
        RateLimitProperties limits = new RateLimitProperties(true, null, Map.of(), 1_000, 200, 2);
        BookMetadataNormalizer normalizer = new BookMetadataNormalizer();
        ELibrarySearchService service = new ELibrarySearchService(repository, resolver, pool,
                new BookMatcher(normalizer), normalizer,
                new ExternalCallBudget(limits, new SimpleMeterRegistry(), Clock.systemUTC()),
                new ELibraryConcurrencyLimiter(limits), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "perLibraryTimeoutMs", SITE_DEADLINE_MS);

        long started = System.nanoTime();
        ELibrarySearchResponse response = service.search("자바의 정석", "남궁성",
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        Map<ELibrarySearchStatus, Long> healthy = response.results().stream()
                .filter(r -> r.libraryId() > DEAD)
                .collect(Collectors.groupingBy(ELibrarySearchResponse.ELibraryResult::status, Collectors.counting()));
        Map<ELibrarySearchStatus, Long> dead = response.results().stream()
                .filter(r -> r.libraryId() <= DEAD)
                .collect(Collectors.groupingBy(ELibrarySearchResponse.ELibraryResult::status, Collectors.counting()));
        System.out.printf("[T4 전자도서관] 죽은 사이트 %d곳 + 정상 %d곳, 스레드 5 | 정상: %s / 죽은: %s / 응답 %dms%n",
                DEAD, TOTAL - DEAD, healthy, dead, elapsedMs);

        assertThat(healthy).containsExactly(Map.entry(ELibrarySearchStatus.SUCCESS, (long) (TOTAL - DEAD)));
        assertThat(dead).containsExactly(Map.entry(ELibrarySearchStatus.TIMEOUT, (long) DEAD));
        // 죽은 사이트 6곳이 스레드 5개에서 두 번에 나뉘어 각자 사이트 마감만큼 쥔다 → 대략 2 × 마감
        assertThat(elapsedMs).isLessThan(4 * SITE_DEADLINE_MS);
    }

    private static ELibrary library(long id, String baseUrl) {
        ELibrary library = ELibrary.builder()
                .name("도서관" + id)
                .baseUrl(baseUrl)
                .vendorType(VendorType.KYOBO)
                .status(ELibraryStatus.ACTIVE)
                .loginRequired(false)
                .build();
        ReflectionTestUtils.setField(library, "id", id);
        return library;
    }
}
