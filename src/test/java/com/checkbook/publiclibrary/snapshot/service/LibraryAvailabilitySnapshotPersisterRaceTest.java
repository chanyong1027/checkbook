package com.checkbook.publiclibrary.snapshot.service;

import com.checkbook.publiclibrary.snapshot.domain.LibraryAvailabilitySnapshot;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.repository.LibraryAvailabilitySnapshotRepository;
import com.checkbook.support.PostgresIntegrationTest;
import org.hibernate.AssertionFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.UnexpectedRollbackException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A(같은 책 동시 첫 조회) 재현: 같은 (ISBN, 도서관)의 첫 저장이 두 트랜잭션에서 겹치면 무슨 일이 생기나.
 * <p>
 * 순서를 시간(sleep)이 아니라 장벽(CyclicBarrier)으로 고정한다 — 두 스레드가 둘 다 '넣기' 직전까지 온 뒤에야
 * 넣게. 리포지토리를 스파이로 감싸 그 자리에서 장벽을 기다리게 한다.
 * <p>
 * 고치기 전(찾고 없으면 save, PostgreSQL 18): 하나는 성공, 하나는 유일 제약 위반(23505), 표에는 1행. 변형 시험은 그 실패가
 * '바로'가 아니라 먼저 넣은 트랜잭션이 끝나기를 기다린 뒤라는 것을 보였다. 고친 뒤(ON CONFLICT 한 문장): 같은 기다림 뒤에
 * 실패 대신 갱신해 둘 다 성공한다 — 단언은 고친 뒤 기준이고, persister를 되돌리면 첫 시험과 커밋 변형이 실패한다.
 * 시행착오 시험(①②)은 고치기 전에 떠올리기 쉬운 다른 고침이 왜 안 되는지를 남긴다 — 고침을 시험 안에서 흉내 내고
 * persister를 부르지 않아, persister를 고친 뒤에도 같은 것을 본다
 */
class LibraryAvailabilitySnapshotPersisterRaceTest extends PostgresIntegrationTest {

    private static final String ISBN = "9788937460449";
    private static final String LIB_CODE = "111001";
    private static final int BARRIER_TIMEOUT_SECONDS = 5;
    // A가 커밋을 미루는 동안 B가 끝나는지 지켜보는 시간. 창을 기대에 맞춰 둘로 나눈다 — 실행기가 느려도 단언이 안 흔들리게:
    // '멈춘다'를 기대하면 짧게(느려져도 멈춘 쪽으로만 틀린다), '끝난다'를 기대하면 길게(끝나는 즉시 돌아와 시간은 안 든다)
    private static final int OBSERVE_STUCK_SECONDS = 2;
    private static final int OBSERVE_FINISH_SECONDS = 10;
    // A가 "끝내라" 신호를 기다리는 상한 — 가장 긴 관찰 창보다 길어야 A가 먼저 포기해 롤백하지 않는다
    private static final int SIGNAL_TIMEOUT_SECONDS = 20;

    @Autowired
    private LibraryAvailabilitySnapshotPersister persister;

    // 진짜 리포지토리를 감싼 스파이 — 평소엔 진짜가 돌고, 아래에서 고른 메서드만 바꾼다
    @MockitoSpyBean
    private LibraryAvailabilitySnapshotRepository repository;

    private ExecutorService threads;

    @BeforeEach
    void setUp() {
        truncate("library_availability_snapshot");
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    void 같은_키의_첫_저장이_겹치면() throws Exception {
        CyclicBarrier bothReadyToInsert = new CyclicBarrier(2);

        // 고치기 전 코드(찾고 없으면 save)의 끼움 자리: 진짜 찾기 → 다른 스레드도 올 때까지 기다리기 → 진짜 결과(없음) 돌려주기
        doAnswer(invocation -> {
            Object found = callRealRepository(invocation);   // 진짜 찾기 결과(Optional) — 첫 저장이면 비어 있다
            bothReadyToInsert.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return found;
        }).when(repository).findByIsbn13AndLibCode(ISBN, LIB_CODE);
        // 고친 코드(ON CONFLICT 한 문장)의 끼움 자리: 둘 다 그 문장을 보내기 '직전'에 만난다.
        // 순서가 위와 반대인 이유 — 진짜를 먼저 부르면 먼저 넣은 쪽이 커밋 전에 장벽에서 기다리고, 다른 쪽은 그 행 때문에
        // 넣기에서 멈춰 장벽에 못 온다(변형 시험에서 본 기다림)
        doAnswer(invocation -> {
            bothReadyToInsert.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return callRealRepository(invocation);
        }).when(repository).upsert(eq(ISBN), eq(LIB_CODE), anyBoolean(), anyBoolean(), anyString(), any(), any());

        // 두 스레드가 같은 키로 동시에 저장한다
        Callable<Void> save = () -> {
            persister.upsert(ISBN, LIB_CODE, true, true);
            return null;
        };
        Future<Void> first = threads.submit(save);
        Future<Void> second = threads.submit(save);

        Throwable firstError = errorOf(first);
        Throwable secondError = errorOf(second);
        System.out.printf("[A 재현] 첫째: %s / 둘째: %s%n", describe(firstError), describe(secondError));

        // 고치기 전: 둘 다 '없음'을 보고 넣어 한쪽이 23505. 고친 뒤: 둘이 같은 문장을 거의 동시에 보내 하나는 넣고 하나는 갱신 —
        // 장벽은 동시 출발만 보장한다(먼저 간 쪽이 커밋까지 끝낸 뒤 둘째가 갈 수도 있다). '커밋 전 행을 기다렸다가 갱신'은
        // 아래 커밋 변형이 결정적으로 보인다. 표는 스파이를 거치지 않고 직접 센다
        List<Throwable> errors = new ArrayList<>();
        if (firstError != null) errors.add(firstError);
        if (secondError != null) errors.add(secondError);
        assertThat(errors).isEmpty();
        verify(repository, times(2)).upsert(eq(ISBN), eq(LIB_CODE), anyBoolean(), anyBoolean(), anyString(), any(), any());   // 둘 다 장벽을 지났다
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from library_availability_snapshot where isbn13 = ? and lib_code = ?",
                Integer.class, ISBN, LIB_CODE);
        assertThat(rows).isEqualTo(1);
    }

    // ===== 변형: A가 넣고 커밋하기 전에 B가 같은 키를 넣으면 =====
    // 유일 검사는 커밋 전인 행도 본다(읽기와 다르다). 그 행을 넣은 트랜잭션이 아직 진행 중이면 중복인지 아직 모르므로,
    // B는 A가 끝날 때까지 멈춘다 — A 롤백이면 B가 넣는다. A 커밋이면 고치기 전엔 B가 23505, 고친 뒤(ON CONFLICT)엔 B가 갱신.
    // 첫 시험과 달리 장벽·스파이가 필요 없다 — A의 트랜잭션을 시험이 직접 열고, 커밋할 때를 정한다

    @Test
    void 커밋_전에_같은_키를_넣고_A가_커밋하면() throws Exception {
        Outcome outcome = overlap(true);
        System.out.println("[A 변형·커밋] " + outcome);

        // 기다림은 그대로, 기다린 뒤가 다르다: 고치기 전엔 B가 23505(A의 값이 남음), 고친 뒤엔 B가 A의 행을 갱신
        assertThat(outcome.bFinishedWhileAOpen()).isFalse();
        assertThat(outcome.bError()).isNull();
        assertThat(outcome.aError()).isNull();
        assertThat(outcome.rows()).isEqualTo(1);
        assertThat(survivingLoanAvailable()).isTrue();    // B가 마지막에 씀
    }

    @Test
    void 커밋_전에_같은_키를_넣고_A가_롤백하면() throws Exception {
        Outcome outcome = overlap(false);
        System.out.println("[A 변형·롤백] " + outcome);

        // A는 스스로 롤백을 고른 것이라 예외가 없다(넣은 행만 사라진다)
        assertThat(outcome.bFinishedWhileAOpen()).isFalse();
        assertThat(outcome.bError()).isNull();
        assertThat(outcome.aError()).isNull();
        assertThat(outcome.rows()).isEqualTo(1);
        assertThat(survivingLoanAvailable()).isTrue();    // B의 행
    }

    /**
     * A: 트랜잭션을 열고 넣는다 → "넣었다" 신호 → 시험이 풀어 줄 때까지 커밋하지 않고 잡고 있다 → 커밋(또는 롤백).
     * B: A의 신호를 받은 뒤 같은 키로 저장한다. 시험은 A를 잡아 둔 채 B가 끝나는지 지켜본 뒤 A를 풀어 준다.
     */
    private Outcome overlap(boolean aCommits) throws Exception {
        CountDownLatch aInserted = new CountDownLatch(1);   // A → 시험: "넣었다"
        CountDownLatch releaseA = new CountDownLatch(1);    // 시험 → A: "이제 끝내라"

        Future<Void> a = threads.submit(() -> {
            // 트랜잭션을 직접 연다 — 이 블록이 끝나야 커밋(또는 롤백)된다
            transactionTemplate.executeWithoutResult(status -> {
                // upsert도 @Transactional이지만 이미 열린 이 트랜잭션에 합류해(기본 전파 REQUIRED) 메서드가 끝나도
                // 커밋하지 않는다. 대출 가능을 B와 다르게 넣어, 남은 행이 누구 것인지 구분한다
                persister.upsert(ISBN, LIB_CODE, true, false);
                aInserted.countDown();
                awaitSignal(releaseA);
                if (!aCommits) {
                    status.setRollbackOnly();   // 블록이 끝날 때 커밋 대신 롤백
                }
            });
            return null;
        });
        awaitSignal(aInserted);

        Future<Void> b = threads.submit(() -> {
            // 바깥 트랜잭션이 없어 upsert가 자기 트랜잭션을 열고, 끝나면 바로 커밋한다
            persister.upsert(ISBN, LIB_CODE, true, true);
            return null;
        });

        boolean bFinishedWhileAOpen = finishesWithin(b, OBSERVE_STUCK_SECONDS);   // 변형 둘 다 B가 멈추는 것을 본다
        releaseA.countDown();
        return new Outcome(bFinishedWhileAOpen, errorOf(b), errorOf(a), countRows());
    }

    private record Outcome(boolean bFinishedWhileAOpen, Throwable bError, Throwable aError, int rows) {
        @Override
        public String toString() {
            return "A 커밋 전 B 끝남=" + bFinishedWhileAOpen + " / B: " + describe(bError) + " / A: " + describe(aError)
                    + " / 행 " + rows;
        }
    }

    // ===== 시행착오 ①: "23505가 나면 같은 트랜잭션 안에서 잡고 이어 가면 되지 않나?" =====
    // 경쟁에서 진 쪽을 바로 만든다: 상대가 먼저 넣고 커밋해 둔 상태에서, '없음'을 봤다고 치고 같은 키로 넣는다.
    // (persister.upsert로 넣으면 충돌이 안 난다 — 고치기 전엔 찾기가 커밋된 상대 행을 보고 갱신으로 갔고, 지금은 ON CONFLICT가 갱신한다)
    // 잡은 뒤 하는 일만 바꿔 가며 본다: 가) 아무것도 안 함 나) JPA로 이어 감(다시 찾기 — "있으면 갱신"의 첫걸음) 다) JPA를 거치지 않고 DB에 직접 질문

    @Test
    void 시행착오1가_같은_트랜잭션에서_23505를_잡고_삼키면() {
        persister.upsert(ISBN, LIB_CODE, true, false);   // 상대(이긴 쪽)가 먼저 넣고 커밋했다

        Throwable error = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                repository.save(snapshot(true));   // '없음'을 봤다고 치고 같은 키로 바로 넣는다
            } catch (DataIntegrityViolationException e) {
                System.out.println("[시행착오 1-가] 잡음: " + describe(e));
                // 잡고 아무것도 하지 않는다 — 삼킨다
            }
        }));
        System.out.println("[시행착오 1-가] 트랜잭션 결과: " + describe(error) + " / 남은 행 대출가능=" + survivingLoanAvailable());

        // 삼켜도 합류한 save가 남긴 '전체 롤백' 표시(global rollback-only)는 남는다 → 커밋 대신 롤백 + 예외
        assertThat(error).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(survivingLoanAvailable()).isFalse();   // 상대 행 그대로
    }

    @Test
    void 시행착오1나_같은_트랜잭션에서_23505를_잡고_JPA로_이어_가면() {
        persister.upsert(ISBN, LIB_CODE, true, false);   // 상대(이긴 쪽)가 먼저 넣고 커밋했다

        Throwable error = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                repository.save(snapshot(true));
            } catch (DataIntegrityViolationException e) {
                System.out.println("[시행착오 1-나] 잡음: " + describe(e));
                repository.findByIsbn13AndLibCode(ISBN, LIB_CODE);   // 이미 있으니 다시 찾아 갱신하려 한다 — 그 첫걸음
            }
        }));
        System.out.println("[시행착오 1-나] 트랜잭션 결과: " + describe(error) + " / 남은 행 대출가능=" + survivingLoanAvailable());

        // DB까지 가지도 못한다: 쿼리를 보내기 전에 Hibernate가 1차 캐시를 DB와 맞추려다(auto flush),
        // INSERT가 실패해 id가 없는 엔티티를 만나 멈춘다 — "예외 뒤엔 세션을 쓰지 말라"
        assertThat(error).isInstanceOf(AssertionFailure.class).hasMessageContaining("null identifier");
        assertThat(survivingLoanAvailable()).isFalse();
    }

    @Test
    void 시행착오1다_같은_트랜잭션에서_23505를_잡고_DB에_바로_다시_물으면() {
        persister.upsert(ISBN, LIB_CODE, true, false);   // 상대(이긴 쪽)가 먼저 넣고 커밋했다

        Throwable error = catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            try {
                repository.save(snapshot(true));
            } catch (DataIntegrityViolationException e) {
                System.out.println("[시행착오 1-다] 잡음: " + describe(e));
                countRows();   // JPA(Hibernate)를 거치지 않고 같은 트랜잭션에서 DB에 바로 묻는다
            }
        }));
        System.out.println("[시행착오 1-다] 트랜잭션 결과: " + describe(error) + " / 남은 행 대출가능=" + survivingLoanAvailable());

        // JPA를 건너뛰면 DB가 막는다: 오류가 난 트랜잭션은 롤백 전까지 모든 문장을 거부(MySQL은 받아 준다)
        assertThat(sqlState(error)).isEqualTo("25P02");
        assertThat(survivingLoanAvailable()).isFalse();
    }

    // ===== 시행착오 ②: "찾을 때 FOR UPDATE로 잠그면 두 트랜잭션이 줄을 서지 않나?" =====
    // 찾기를 잠금 찾기로 바꾼 upsert를 시험 안에서 흉내 낸다(운영 리포지토리는 건드리지 않음).
    // 변형 시험과 같은 틀: A가 잠금 찾기를 하고 커밋 전에 멈춰 있는 동안, B도 잠금 찾기를 지나갈 수 있나?
    // 가) 첫 저장(행 없음) 나) 이미 있는 행 — 둘을 나란히 본다

    @Test
    void 시행착오2가_찾기를_FOR_UPDATE로_잠가도_첫_저장이면() throws Exception {
        Outcome outcome = lockingOverlap(OBSERVE_FINISH_SECONDS);   // B가 끝나기를 기대
        System.out.println("[시행착오 2-가] " + outcome + " / 남은 행 대출가능=" + survivingLoanAvailable());

        // 예측: 행이 없어 잠글 것이 없다 → B는 멈추지 않고 넣고 커밋, 나중에 넣는 A가 23505
        assertThat(outcome.bFinishedWhileAOpen()).isTrue();
        assertThat(outcome.bError()).isNull();
        assertThat(sqlState(outcome.aError())).isEqualTo("23505");
        assertThat(outcome.rows()).isEqualTo(1);
        assertThat(survivingLoanAvailable()).isTrue();   // B의 행
    }

    @Test
    void 시행착오2나_찾기를_FOR_UPDATE로_잠그면_이미_있는_행은() throws Exception {
        persister.upsert(ISBN, LIB_CODE, false, false);   // 행이 이미 있다(전날 저장해 둔 스냅샷)

        Outcome outcome = lockingOverlap(OBSERVE_STUCK_SECONDS);    // B가 멈추기를 기대
        System.out.println("[시행착오 2-나] " + outcome + " / 남은 행 대출가능=" + survivingLoanAvailable());

        // 예측: A가 찾은 행을 잠갔다 → B는 A가 끝날 때까지 기다렸다가 갱신, 마지막에 쓴 B의 값이 남는다
        assertThat(outcome.bFinishedWhileAOpen()).isFalse();
        assertThat(outcome.bError()).isNull();
        assertThat(outcome.aError()).isNull();
        assertThat(outcome.rows()).isEqualTo(1);
        assertThat(survivingLoanAvailable()).isTrue();   // B가 마지막에 씀
    }

    /**
     * A: 트랜잭션 안에서 잠금 찾기 → "잠갔다" 신호 → 풀어 줄 때까지 커밋 전으로 멈춤 → 넣기(없었으면)/갱신(있었으면) → 커밋.
     * B: A의 신호를 받은 뒤 같은 일을 멈춤 없이. 시험은 A를 잡아 둔 채 B가 끝나는지 지켜본 뒤 A를 풀어 준다.
     * A는 대출가능 false, B는 true로 쓴다 — 남은 값으로 누가 마지막에 썼는지 안다.
     */
    private Outcome lockingOverlap(int observeSeconds) throws Exception {
        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        Future<Void> a = threads.submit(() -> {
            transactionTemplate.executeWithoutResult(status -> lockingUpsert(false, () -> {
                aLocked.countDown();
                awaitSignal(releaseA);
            }));
            return null;
        });
        awaitSignal(aLocked);

        Future<Void> b = threads.submit(() -> {
            transactionTemplate.executeWithoutResult(status -> lockingUpsert(true, () -> { }));
            return null;
        });

        boolean bFinishedWhileAOpen = finishesWithin(b, observeSeconds);
        releaseA.countDown();
        return new Outcome(bFinishedWhileAOpen, errorOf(b), errorOf(a), countRows());
    }

    /** 잠금 찾기를 쓰는 upsert 흉내. afterLock은 찾기(잠그기) 직후에 끼우는 자리 — A가 여기서 멈춘다 */
    private void lockingUpsert(boolean loanAvailable, Runnable afterLock) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from library_availability_snapshot where isbn13 = ? and lib_code = ? for update",
                Long.class, ISBN, LIB_CODE);
        afterLock.run();
        if (ids.isEmpty()) {
            repository.save(snapshot(loanAvailable));
        } else {
            jdbcTemplate.update("update library_availability_snapshot set loan_available = ? where id = ?",
                    loanAvailable, ids.get(0));
        }
    }

    // ---- 뼈대 도우미 (재현과 상관없음) ----

    private int countRows() {
        return jdbcTemplate.queryForObject(
                "select count(*) from library_availability_snapshot where isbn13 = ? and lib_code = ?",
                Integer.class, ISBN, LIB_CODE);
    }

    /** 같은 키의 새 스냅샷 엔티티 — persister가 처음 넣을 때 만드는 것과 같은 모양 */
    private static LibraryAvailabilitySnapshot snapshot(boolean loanAvailable) {
        Instant now = Instant.now();
        return LibraryAvailabilitySnapshot.builder()
                .isbn13(ISBN).libCode(LIB_CODE)
                .hasBook(true).loanAvailable(loanAvailable)
                .sourceStatus(SnapshotSourceStatus.SUCCESS)
                .lastFetchedAt(now).expiresAt(now.plus(LibraryAvailabilitySnapshotPersister.TTL_HOURS, ChronoUnit.HOURS))
                .build();
    }

    /** 남은 행의 loan_available — A는 false, B는 true로 넣어 누구 행인지 구분한다 */
    private boolean survivingLoanAvailable() {
        return jdbcTemplate.queryForObject(
                "select loan_available from library_availability_snapshot where isbn13 = ? and lib_code = ?",
                Boolean.class, ISBN, LIB_CODE);
    }

    /** 신호를 기다린다. 상대가 죽어 신호가 안 오면 영원히 기다리지 않고 실패 */
    private static void awaitSignal(CountDownLatch latch) {
        try {
            if (!latch.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("신호가 " + SIGNAL_TIMEOUT_SECONDS + "초 안에 오지 않음");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 정해진 시간 안에 끝났나(성공이든 실패든). 여기서 시간은 '지켜보는 창'이다 — 안 끝났으면 멈춰 있다는 뜻 */
    private static boolean finishesWithin(Future<?> future, int seconds) throws InterruptedException {
        try {
            future.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * 스파이가 감싼 진짜 리포지토리에게 넘겨 실제로 실행한다. 스파이 예시의 {@code invocation.callRealMethod()} 자리.
     * 스프링 데이터 리포지토리는 인터페이스(뒤에서 JDK 프록시가 일함)라 '자기 진짜 메서드'가 없어 callRealMethod()가
     * "Cannot call abstract real method"로 실패한다. @MockitoSpyBean은 이런 빈에 "진짜 빈에게 넘기는" 기본 동작을 달아
     * 두므로 그것을 직접 부른다
     */
    private Object callRealRepository(InvocationOnMock invocation) throws Throwable {
        return Mockito.mockingDetails(repository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    /** 스레드가 성공했으면 null, 실패했으면 그 예외. 10초 안에 안 끝나면 시험 실패 */
    private static Throwable errorOf(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /** 예외 사슬에서 DB가 준 SQLState(예: 23505)를 찾는다. 없으면 null */
    protected static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String describe(Throwable error) {
        return error == null ? "예외 없음" : error.getClass().getSimpleName() + " (SQLState " + sqlState(error) + ")";
    }
}
