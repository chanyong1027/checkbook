package com.checkbook.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Test
    void executorsAreCreatedWithExpectedBeanMethods() {
        ReflectionTestUtils.setField(asyncConfig, "eLibraryPoolSize", 5);
        ReflectionTestUtils.setField(asyncConfig, "searchPoolSize", 3);
        ReflectionTestUtils.setField(asyncConfig, "publicLibraryPoolSize", 20);

        ExecutorService eLibraryExecutor = asyncConfig.eLibraryExecutor();
        ExecutorService searchExecutor = asyncConfig.searchExecutor();
        ExecutorService publicLibraryExecutor = asyncConfig.publicLibraryExecutor();

        assertThat(eLibraryExecutor).isNotNull();
        assertThat(searchExecutor).isNotNull();
        assertThat(publicLibraryExecutor).isNotNull();
        assertThat(eLibraryExecutor).isNotSameAs(searchExecutor);
        assertThat(searchExecutor).isNotSameAs(publicLibraryExecutor);
        assertThat(publicLibraryExecutor).isNotSameAs(eLibraryExecutor);
        assertThat(((ThreadPoolExecutor) eLibraryExecutor).getCorePoolSize()).isEqualTo(5);
        assertThat(((ThreadPoolExecutor) searchExecutor).getCorePoolSize()).isEqualTo(3);
        assertThat(((ThreadPoolExecutor) publicLibraryExecutor).getCorePoolSize()).isEqualTo(20);

        eLibraryExecutor.shutdown();
        searchExecutor.shutdown();
        publicLibraryExecutor.shutdown();
    }
}
