package com.checkbook.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 풀_3개가_분리_생성되고_코어_사이즈_메트릭이_노출된다() {
        ReflectionTestUtils.setField(asyncConfig, "eLibraryPoolSize", 5);
        ReflectionTestUtils.setField(asyncConfig, "eLibraryQueueCapacity", 25);
        ReflectionTestUtils.setField(asyncConfig, "searchPoolSize", 12);
        ReflectionTestUtils.setField(asyncConfig, "searchQueueCapacity", 24);
        ReflectionTestUtils.setField(asyncConfig, "publicLibraryPoolSize", 40);
        ReflectionTestUtils.setField(asyncConfig, "publicLibraryQueueCapacity", 40);
        ReflectionTestUtils.setField(asyncConfig, "rejectionPolicy", "abort");

        ExecutorService eLibraryExecutor = asyncConfig.eLibraryExecutor(registry);
        ExecutorService searchExecutor = asyncConfig.searchExecutor(registry);
        ExecutorService publicLibraryExecutor = asyncConfig.publicLibraryExecutor(registry);

        assertThat(eLibraryExecutor).isNotSameAs(searchExecutor);
        assertThat(searchExecutor).isNotSameAs(publicLibraryExecutor);

        assertThat(coreSize("eLibraryExecutor")).isEqualTo(5.0);
        assertThat(coreSize("searchExecutor")).isEqualTo(12.0);
        assertThat(coreSize("publicLibraryExecutor")).isEqualTo(40.0);

        eLibraryExecutor.shutdown();
        searchExecutor.shutdown();
        publicLibraryExecutor.shutdown();
    }

    @Test
    void boundedPool은_고정_사이즈와_bounded_큐와_지정한_거절_정책을_가진다() {
        ThreadPoolExecutor pool = AsyncConfig.newBoundedPool(
                4, 8, "test-", new ThreadPoolExecutor.AbortPolicy());

        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(pool.getQueue().remainingCapacity()).isEqualTo(8);
        assertThat(pool.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

        pool.shutdown();
    }

    @Test
    void callerRuns_정책은_큐가_가득_차면_제출자_스레드가_직접_실행한다() {
        ThreadPoolExecutor pool = AsyncConfig.newBoundedPool(
                1, 1, "test-", new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            pool.execute(() -> awaitQuietly(blocker));  // 유일한 스레드 점유
            pool.execute(() -> { });                     // 큐(1칸) 점유

            AtomicReference<String> executedOn = new AtomicReference<>();
            pool.execute(() -> executedOn.set(Thread.currentThread().getName())); // 초과분

            // CallerRunsPolicy: 초과 태스크는 제출자(테스트 메인 스레드)가 즉시 실행 → backpressure
            assertThat(executedOn.get()).isEqualTo(Thread.currentThread().getName());
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void abort_정책은_큐가_가득_차면_동기로_거절_예외를_던진다() {
        ThreadPoolExecutor pool = AsyncConfig.newBoundedPool(
                1, 1, "test-", new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            pool.execute(() -> awaitQuietly(blocker));
            pool.execute(() -> { });

            // 이 동기 예외가 SearchService의 거절 안전 헬퍼(submitSafely)가 필요한 이유
            assertThatThrownBy(() -> pool.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 거절이_발생하면_rejected_카운터가_증가한다() {
        ReflectionTestUtils.setField(asyncConfig, "searchPoolSize", 1);
        ReflectionTestUtils.setField(asyncConfig, "searchQueueCapacity", 1);
        ReflectionTestUtils.setField(asyncConfig, "rejectionPolicy", "abort");

        ExecutorService pool = asyncConfig.searchExecutor(registry);
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            pool.execute(() -> awaitQuietly(blocker));
            pool.execute(() -> { });

            assertThatThrownBy(() -> pool.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(registry.get("executor.rejected")
                    .tag("name", "searchExecutor").counter().count()).isEqualTo(1.0);
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double coreSize(String executorName) {
        return registry.get("executor.pool.core")
                .tag("name", executorName)
                .gauge()
                .value();
    }
}
