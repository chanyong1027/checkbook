package com.checkbook.common.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
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

    /** 종료 drain 총 예산 — docker stop grace(10s)에서 컨텍스트 종료 오버헤드를 뺀 값 */
    @Value("${async.shutdown-grace-ms:5000}")
    private long shutdownGraceMs;

    private final List<ExecutorService> managedPools = new ArrayList<>();

    @Bean(name = "eLibraryExecutor", destroyMethod = "")
    public ExecutorService eLibraryExecutor(MeterRegistry meterRegistry) {
        return track(ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(eLibraryPoolSize, eLibraryQueueCapacity, "elib-",
                        resolveRejectionPolicy(meterRegistry, "eLibraryExecutor")),
                "eLibraryExecutor"));
    }

    @Bean(name = "searchExecutor", destroyMethod = "")
    public ExecutorService searchExecutor(MeterRegistry meterRegistry) {
        return track(ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(searchPoolSize, searchQueueCapacity, "search-",
                        resolveRejectionPolicy(meterRegistry, "searchExecutor")),
                "searchExecutor"));
    }

    @Bean(name = "publicLibraryExecutor", destroyMethod = "")
    public ExecutorService publicLibraryExecutor(MeterRegistry meterRegistry) {
        return track(ExecutorServiceMetrics.monitor(meterRegistry,
                newBoundedPool(publicLibraryPoolSize, publicLibraryQueueCapacity, "publib-",
                        resolveRejectionPolicy(meterRegistry, "publicLibraryExecutor")),
                "publicLibraryExecutor"));
    }

    private ExecutorService track(ExecutorService pool) {
        managedPools.add(pool);
        return pool;
    }

    /**
     * SIGTERM(배포) 경로의 graceful drain. TPE.shutdown()은 논블로킹이고 JVM은 shutdown hook
     * 완료 후 non-daemon 여부와 무관하게 halt하므로, 진행 중 작업 완료 보장에는 블로킹 대기가 필수.
     * 총 예산(기본 5s, grace 10s 내) 안에서 대기 후 잔여는 shutdownNow(인터럽트).
     * eLibrary 풀은 작업 특성상(per-library 15s) 예산 내 완주 불가 — 인터럽트 열화 대상.
     */
    @PreDestroy
    void shutdownGracefully() {
        managedPools.forEach(ExecutorService::shutdown);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownGraceMs);
        for (ExecutorService pool : managedPools) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            try {
                if (remaining <= 0 || !pool.awaitTermination(remaining, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
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
        // daemon + 블로킹 drain 조합이 안전한 이유:
        // - 진행 중 작업 보호는 shutdownGracefully()의 awaitTermination이 담당
        //   (SIGTERM 경로에서 JVM은 hook 완료 후 non-daemon과 무관하게 halt — daemon으로는 보호 불가)
        // - daemon은 비-SIGTERM 종료 경로(테스트 JVM 등)에서 인터럽트 불응 태스크(블로킹 소켓 I/O)가
        //   JVM 종료를 무기한 막는 회귀를 차단
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
