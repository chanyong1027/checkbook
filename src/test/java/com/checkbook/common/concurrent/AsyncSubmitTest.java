package com.checkbook.common.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MDC는 스레드마다 따로 있어 풀로 저절로 넘어가지 않는다. 제출 시점의 값이 넘어가고, 풀 스레드에 남지 않으며,
 * 호출자 스레드에서 돌아도(callerRuns) 호출자의 값을 잃지 않는지 본다.
 */
class AsyncSubmitTest {

    private static final String KEY = "traceId";
    private static final long TIMEOUT_SECONDS = 5;

    private final List<ExecutorService> pools = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        pools.forEach(ExecutorService::shutdownNow);
        MDC.clear();
    }

    @Test
    void 풀_스레드에서_제출한_요청의_ID가_보인다() throws Exception {
        ExecutorService pool = singleThreadPool("pool-");
        MDC.put(KEY, "request-a");

        String seen = AsyncSubmit.submitSafely(() -> MDC.get(KEY), pool).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(seen).isEqualTo("request-a");
    }

    @Test
    void 작업을_끝낸_풀_스레드에서_도는_exceptionally에서도_ID가_보인다() throws Exception {
        // PublicLibraryAvailabilityService의 "bookExist 호출 실패" 경고가 이 경로다. 작업(Supplier)만 감싸면 여기서 ID가 사라진다
        ExecutorService pool = singleThreadPool("pool-");
        MDC.put(KEY, "request-a");
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>();

        CompletableFuture<String> staged = AsyncSubmit.<String>submitSafely(() -> {
            awaitRelease();
            throw new IllegalStateException("호출 실패");
        }, pool).exceptionally(e -> {
            thread.set(Thread.currentThread().getName());
            seen.set(MDC.get(KEY));
            return null;
        });
        release.countDown();
        staged.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(thread.get()).as("후속 단계가 호출자가 아니라 풀 스레드에서 돌아야 이 시험의 뜻이 있다").startsWith("pool-");
        assertThat(seen.get()).isEqualTo("request-a");
    }

    @Test
    void 풀_안에서_다른_풀로_다시_넘겨도_ID가_이어진다() throws Exception {
        // SearchService → search 풀 → publicLibrary 풀
        ExecutorService outer = singleThreadPool("outer-");
        ExecutorService inner = singleThreadPool("inner-");
        MDC.put(KEY, "request-a");

        String seen = AsyncSubmit.submitSafely(
                () -> AsyncSubmit.submitSafely(() -> MDC.get(KEY), inner).join(), outer
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(seen).isEqualTo("request-a");
    }

    @Test
    void 작업이_끝나면_재사용되는_풀_스레드에_ID가_남지_않는다() throws Exception {
        ExecutorService pool = singleThreadPool("pool-");
        MDC.put(KEY, "request-a");

        AsyncSubmit.submitSafely(() -> "끝", pool).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(pool.submit(() -> MDC.get(KEY)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void ID_없이_제출한_작업에는_풀_스레드에_있던_값이_보이지_않는다() throws Exception {
        ExecutorService pool = singleThreadPool("pool-");
        pool.submit(() -> MDC.put(KEY, "stale")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String seen = AsyncSubmit.submitSafely(() -> MDC.get(KEY), pool).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(seen).isNull();
    }

    @Test
    void 풀이_거절하면_실패한_future가_되고_호출자의_ID는_그대로다() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        pools.add(pool);
        pool.shutdown(); // 이후 제출은 전부 거절된다
        MDC.put(KEY, "request-a");

        CompletableFuture<String> future = AsyncSubmit.submitSafely(() -> "안 돈다", pool);

        assertThat(future).isCompletedExceptionally();
        assertThat(future.exceptionNow()).isInstanceOf(RejectedExecutionException.class);
        assertThat(MDC.get(KEY)).isEqualTo("request-a");
    }

    @Test
    void callerRuns로_호출자_스레드에서_돌아도_끝난_뒤_호출자의_ID가_남는다() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        pools.add(pool);
        CountDownLatch busy = new CountDownLatch(1);
        pool.execute(() -> {
            busy.countDown();
            awaitRelease();
        });
        assertThat(busy.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        MDC.put(KEY, "request-a");
        String caller = Thread.currentThread().getName();

        String ranOn = AsyncSubmit.submitSafely(() -> Thread.currentThread().getName(), pool).join();

        assertThat(ranOn).as("풀이 찼으니 호출자가 직접 돈다").isEqualTo(caller);
        assertThat(MDC.get(KEY)).as("지우면 이 요청의 나머지 로그에서 ID가 사라진다").isEqualTo("request-a");
    }

    @Test
    void 시작_전에_취소된_작업도_풀_스레드에_ID를_남기지_않는다() throws Exception {
        // PublicLibraryBatchService는 마감 뒤 큐에 있는 작업을 cancel한다. 실행기로 넘긴 Runnable은 그래도 한 번 돈다
        ExecutorService pool = singleThreadPool("pool-");
        pool.execute(this::awaitRelease);
        MDC.put(KEY, "request-a");
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<String> queued = AsyncSubmit.submitSafely(() -> {
            ran.set(true);
            return "안 돈다";
        }, pool);
        queued.cancel(true);
        release.countDown();

        assertThat(pool.submit(() -> MDC.get(KEY)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNull();
        assertThat(ran).isFalse();
    }

    private ExecutorService singleThreadPool(String prefix) {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, prefix + "1"));
        pools.add(pool);
        return pool;
    }

    private void awaitRelease() {
        try {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
