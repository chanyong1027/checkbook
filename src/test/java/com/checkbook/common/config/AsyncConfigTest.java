package com.checkbook.common.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Test
    void executorsAreCreatedWithExpectedBeanMethods() {
        ExecutorService eLibraryExecutor = asyncConfig.eLibraryExecutor();
        ExecutorService searchExecutor = asyncConfig.searchExecutor();
        ExecutorService publicLibraryExecutor = asyncConfig.publicLibraryExecutor();

        assertThat(eLibraryExecutor).isNotNull();
        assertThat(searchExecutor).isNotNull();
        assertThat(publicLibraryExecutor).isNotNull();
        assertThat(eLibraryExecutor).isNotSameAs(searchExecutor);
        assertThat(searchExecutor).isNotSameAs(publicLibraryExecutor);
        assertThat(publicLibraryExecutor).isNotSameAs(eLibraryExecutor);

        eLibraryExecutor.shutdown();
        searchExecutor.shutdown();
        publicLibraryExecutor.shutdown();
    }
}
