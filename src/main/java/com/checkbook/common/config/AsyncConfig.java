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

    @Value("${elibrary.thread-pool-size:5}")
    private int eLibraryPoolSize;

    @Value("${search.executor-pool-size:3}")
    private int searchPoolSize;

    @Value("${public-library.executor-pool-size:20}")
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
