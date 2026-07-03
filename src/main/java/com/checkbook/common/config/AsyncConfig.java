package com.checkbook.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {

    // 기본값 없음(fail-fast): yaml이 사이징의 SSOT. 코드 기본값을 두면 키 오타·프로파일 누락 시
    // 경고 없이 구 값으로 폴백하는 함정이 생긴다 (실측으로 병목임을 확인한 3/20으로 되돌아가는 최악 케이스)
    @Value("${elibrary.thread-pool-size}")
    private int eLibraryPoolSize;

    @Value("${search.executor-pool-size}")
    private int searchPoolSize;

    @Value("${public-library.executor-pool-size}")
    private int publicLibraryPoolSize;

    @Bean(name = "eLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService eLibraryExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newFixedThreadPool(eLibraryPoolSize), "eLibraryExecutor");
    }

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newFixedThreadPool(searchPoolSize), "searchExecutor");
    }

    @Bean(name = "publicLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService publicLibraryExecutor(MeterRegistry meterRegistry) {
        return ExecutorServiceMetrics.monitor(
                meterRegistry, Executors.newFixedThreadPool(publicLibraryPoolSize), "publicLibraryExecutor");
    }
}
