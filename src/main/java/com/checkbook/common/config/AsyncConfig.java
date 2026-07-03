package com.checkbook.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAsync
public class AsyncConfig {

    // 기본값 없음(fail-fast): yaml이 사이징의 SSOT. 코드 기본값을 두면 키 오타·프로파일 누락 시
    // 경고 없이 구 값으로 폴백하는 함정이 생긴다 (실측으로 병목임을 확인한 3/20으로 되돌아가는 최악 케이스)
    @Value("${elibrary.thread-pool-size}")
    private int eLibraryPoolSize;

    @Value("${elibrary.queue-capacity}")
    private int eLibraryQueueCapacity;

    @Value("${search.executor-pool-size}")
    private int searchPoolSize;

    @Value("${search.executor-queue-capacity}")
    private int searchQueueCapacity;

    @Value("${public-library.executor-pool-size}")
    private int publicLibraryPoolSize;

    @Value("${public-library.executor-queue-capacity}")
    private int publicLibraryQueueCapacity;

    /**
     * abort — fault A/B 실측으로 확정 (2026-07-04, VU12 + 1900ms 주입):
     * callerRuns는 인라인 실행이 제출 루프·데드라인 예산을 점유해 FAILED 15.8%, max 41.5s,
     * 처리량 43%로 붕괴. abort는 FAILED 0.05%에 부분 결과로 우아하게 열화.
     * callerRuns 선택지는 A/B 재검(설정 변경 시)을 위해 유지.
     */
    @Value("${async.rejection-policy:abort}")
    private String rejectionPolicy;

    @Bean(name = "eLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService eLibraryExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(eLibraryPoolSize, eLibraryQueueCapacity, "elib-",
                        resolveRejectionPolicy(meterRegistry, "eLibraryExecutor")),
                "eLibraryExecutor");
    }

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(searchPoolSize, searchQueueCapacity, "search-",
                        resolveRejectionPolicy(meterRegistry, "searchExecutor")),
                "searchExecutor");
    }

    @Bean(name = "publicLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService publicLibraryExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(publicLibraryPoolSize, publicLibraryQueueCapacity, "publib-",
                        resolveRejectionPolicy(meterRegistry, "publicLibraryExecutor")),
                "publicLibraryExecutor");
    }

    private RejectedExecutionHandler resolveRejectionPolicy(MeterRegistry meterRegistry, String executorName) {
        RejectedExecutionHandler base = switch (rejectionPolicy) {
            case "abort" -> new ThreadPoolExecutor.AbortPolicy();
            case "callerRuns" -> new ThreadPoolExecutor.CallerRunsPolicy();
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 async.rejection-policy: " + rejectionPolicy);
        };
        // ExecutorServiceMetrics는 거절 수를 노출하지 않음 — 정책 A/B 측정에서 거절량을
        // FAILED율로 간접 추정하지 않고 executor_rejected_total{name=...}로 직접 관측
        Counter rejected = Counter.builder("executor.rejected")
                .tag("name", executorName)
                .description("거절 정책 발동 횟수 (abort=태스크 드롭, callerRuns=제출 스레드 인라인 실행)")
                .register(meterRegistry);
        return (runnable, executor) -> {
            rejected.increment();
            base.rejectedExecution(runnable, executor);
        };
    }

    /**
     * bounded queue: 무제한 큐(newFixedThreadPool 기본)로 인한 "데드라인 지난 작업 무한 적체"를
     * 차단한다 — baseline 실측(하네스 v2): 부하 종료 시점 큐 470, 유령 작업이 62초간 풀 점유.
     * 거절 정책은 호출부에서 주입.
     */
    static ThreadPoolExecutor newBoundedPool(
            int poolSize, int queueCapacity, String threadPrefix, RejectedExecutionHandler rejectionHandler) {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectionHandler);
    }
}
