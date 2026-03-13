package com.checkbook.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    @Bean(name = "eLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService eLibraryExecutor() {
        return Executors.newFixedThreadPool(5);
    }

    @Bean(name = "searchExecutor", destroyMethod = "shutdown")
    public ExecutorService searchExecutor() {
        return Executors.newFixedThreadPool(3);
    }

    @Bean(name = "publicLibraryExecutor", destroyMethod = "shutdown")
    public ExecutorService publicLibraryExecutor() {
        return Executors.newFixedThreadPool(20);
    }
}
