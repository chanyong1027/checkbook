package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.support.PostgresIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A(같은 책 동시 첫 조회) 전후 수치 — T4 결정 카드 PR ② 완료 기준.
 * <p>
 * 실제 배선(스냅샷 읽기 → 리프레셔 → 서킷 → 저장)과 실제 PostgreSQL에서, 라운드마다 새 책 하나를 같은 도서관에 대해
 * 여러 명이 출발 신호에 맞춰 동시에 처음 조회한다. 정보나루만 가짜(고정 지연 뒤 '있음·대출가능')다 — 실제 API로 부하를 주지 않는다.
 * 장벽 없이 실제 경쟁에 맡기므로 결과는 확률적이다(결정적 재현은 PersisterRaceTest).
 * <p>
 * 조건이 최악 쪽이다: 모든 조회가 '동시 첫 조회'이고, 지연이 같아 둘이 거의 같은 순간 저장에 닿는다. 운영 예상치가 아니라
 * 재현 조건의 수치로 쓴다. 서킷은 운영 값으로 되돌려 잰다(test 프로필은 창 2라 금방 열린다).
 * <p>
 * 두 질문을 따로 잰다(섞으면 고치기 전 코드에서 서킷이 초반에 열려 30초 동안 나머지 라운드를 다 막아, 충돌률이 '막힘'에 묻힌다).
 * <ul>
 *   <li>M1 충돌률 — 라운드마다 서킷을 초기화해 서킷 영향을 뺀다. 200라운드(충돌률 50% 근처에서 95% 신뢰구간 약 ±7%p)</li>
 *   <li>M2 파급 — 운영 서킷 설정 그대로 이어서. 충돌이 서킷을 몇 번째 호출에서 여나</li>
 * </ul>
 * M2의 '막힌 조회 수'는 비율로 인용하지 않는다: 서킷은 한 번 열리면 30초(wait-duration) 동안 닫히지 않는데 이 측정은
 * 몇 초면 끝나, 열린 뒤 남은 라운드는 전부 막힐 수밖에 없다(비율은 라운드 수와 30초가 정한다). 운영에서 뜻하는 바는
 * "열린 뒤 30초 동안 모든 도서관의 정보나루 조회가 막힌다"(서킷은 도서관 구분 없이 하나)이다.
 * 고친 뒤 '충돌 0'은 겹침이 없었다는 뜻이 아니다 — 겹침은 정보나루 실제 호출 수(라운드당 2번)로 보인다.
 * 동시 인원은 2명 — 우리 트래픽에서 같은 키가 겹칠 수 있는 모양(새로고침·연타, 근처 두 사람)이 이것뿐이다.
 * <p>
 * 출력 전용(검증 없음). benchmark 태그라 test·postgresTest에서 빠진다 — PostgreSQL 실행기에서
 * {@code ./gradlew benchmarkTest --tests "*SnapshotConcurrencyBenchmark"}
 */
@Tag("benchmark")
@TestPropertySource(properties = {
        // application.yaml의 운영 값과 같게
        "resilience4j.circuitbreaker.instances.datanaru.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.datanaru.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.datanaru.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.datanaru.wait-duration-in-open-state="
                + LibraryAvailabilitySnapshotConcurrencyBenchmark.OPEN_WAIT,
        "resilience4j.circuitbreaker.instances.datanaru.permitted-calls-in-half-open-state=3"
})
class LibraryAvailabilitySnapshotConcurrencyBenchmark extends PostgresIntegrationTest {

    private static final int USERS = 2;
    private static final long DATANARU_DELAY_MS = 50;
    private static final String LIB_CODE = "111001";
    static final String OPEN_WAIT = "30s";   // 클래스 애노테이션(@TestPropertySource)이 읽어서 private이 아니다

    @Autowired
    LibraryAvailabilitySnapshotService snapshotService;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    DatanaruClient datanaruClient;

    // 진짜 저장을 그대로 하되, 실패를 세려고 감싼다
    @MockitoSpyBean
    LibraryAvailabilitySnapshotPersister persister;

    @Test
    void M1_충돌률_라운드마다_서킷_초기화() throws Exception {
        measure("M1 충돌률", 200, true);
    }

    @Test
    void M2_파급_운영_서킷_설정으로_이어서() throws Exception {
        measure("M2 파급", 100, false);
    }

    private void measure(String label, int rounds, boolean resetCircuitEachRound) throws Exception {
        int users = USERS;
        truncate("library_availability_snapshot");
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("datanaru");
        circuitBreaker.reset();

        AtomicInteger datanaruCalls = new AtomicInteger();
        when(datanaruClient.bookExist(anyString(), eq(LIB_CODE))).thenAnswer(invocation -> {
            datanaruCalls.incrementAndGet();
            Thread.sleep(DATANARU_DELAY_MS);
            return new DatanaruBookExistResult(LIB_CODE, true, true);
        });

        AtomicInteger persistConflicts = new AtomicInteger();
        AtomicInteger persistOtherErrors = new AtomicInteger();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable e) {
                (("23505".equals(sqlState(e))) ? persistConflicts : persistOtherErrors).incrementAndGet();
                throw e;
            }
        }).when(persister).upsert(anyString(), anyString(), anyBoolean(), anyBoolean());

        // 앞 시험이 단 구독자는 그 시험의 카운터를 올릴 뿐이라 섞이지 않는다
        AtomicInteger circuitRecordedFailures = new AtomicInteger();
        AtomicInteger circuitRejected = new AtomicInteger();
        AtomicInteger circuitOpened = new AtomicInteger();
        AtomicInteger callsBeforeFirstOpen = new AtomicInteger(-1);
        circuitBreaker.getEventPublisher()
                .onError(event -> circuitRecordedFailures.incrementAndGet())
                .onCallNotPermitted(event -> circuitRejected.incrementAndGet())
                .onStateTransition(event -> {
                    if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                        circuitOpened.incrementAndGet();
                        callsBeforeFirstOpen.compareAndSet(-1, datanaruCalls.get());
                    }
                });

        int success = 0;
        int failed = 0;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        long startedAt = System.nanoTime();
        try {
            for (int round = 0; round < rounds; round++) {
                if (resetCircuitEachRound) {
                    circuitBreaker.reset();
                }
                String isbn = String.format("979%010d", round);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<LibraryAvailabilityResult>> futures = new ArrayList<>();
                for (int u = 0; u < users; u++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return snapshotService.getAvailability(isbn, LIB_CODE);
                    }));
                }
                go.countDown();
                for (Future<LibraryAvailabilityResult> future : futures) {
                    if (future.get(10, TimeUnit.SECONDS).sourceStatus() == SnapshotSourceStatus.SUCCESS) {
                        success++;
                    } else {
                        failed++;
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Integer savedKeys = jdbcTemplate.queryForObject(
                "select count(distinct isbn13) from library_availability_snapshot where lib_code = ?", Integer.class, LIB_CODE);
        Integer duplicateKeys = jdbcTemplate.queryForObject(
                "select count(*) from (select isbn13 from library_availability_snapshot where lib_code = ?"
                        + " group by isbn13 having count(*) > 1) d", Integer.class, LIB_CODE);

        System.out.printf("[A 수치] %s — 동시 %d명 × %d라운드%s (정보나루 가짜 지연 %dms, %dms 걸림)%n",
                label, users, rounds, resetCircuitEachRound ? ", 라운드마다 서킷 초기화" : ", 운영 서킷 설정",
                DATANARU_DELAY_MS, elapsedMs);
        System.out.printf("  조회 %d: SUCCESS %d / FAILED %d (그중 서킷이 막은 것 %d, 정보나루가 답했는데 버린 것 %d)%n",
                users * rounds, success, failed, circuitRejected.get(), failed - circuitRejected.get());
        System.out.printf("  정보나루 실제 호출 %d / 저장 충돌(23505) %d · 그 밖의 저장 실패 %d%n",
                datanaruCalls.get(), persistConflicts.get(), persistOtherErrors.get());
        System.out.printf("  서킷이 실패로 센 호출 %d / 열림 %d번 / 끝 상태 %s%n",
                circuitRecordedFailures.get(), circuitOpened.get(), circuitBreaker.getState());
        if (callsBeforeFirstOpen.get() >= 0) {
            System.out.printf("  처음 열림: 정보나루 %d번째 호출 뒤 — 열리면 %s 동안 닫히지 않는데 측정은 %dms라 그 뒤 라운드는 다 막힌다%n",
                    callsBeforeFirstOpen.get(), OPEN_WAIT, elapsedMs);
        }
        System.out.printf("  저장된 책 %d/%d · 중복 행 있는 책 %d · 충돌률(충돌 ÷ 라운드) %.1f%%%n",
                savedKeys, rounds, duplicateKeys, 100.0 * persistConflicts.get() / rounds);
    }

    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
