package com.checkbook.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 풀_3개가_분리_생성되고_코어_사이즈_메트릭이_노출된다() {
        ReflectionTestUtils.setField(asyncConfig, "eLibraryPoolSize", 5);
        ReflectionTestUtils.setField(asyncConfig, "searchPoolSize", 3);
        ReflectionTestUtils.setField(asyncConfig, "publicLibraryPoolSize", 20);

        ExecutorService eLibraryExecutor = asyncConfig.eLibraryExecutor(registry);
        ExecutorService searchExecutor = asyncConfig.searchExecutor(registry);
        ExecutorService publicLibraryExecutor = asyncConfig.publicLibraryExecutor(registry);

        assertThat(eLibraryExecutor).isNotSameAs(searchExecutor);
        assertThat(searchExecutor).isNotSameAs(publicLibraryExecutor);

        assertThat(coreSize("eLibraryExecutor")).isEqualTo(5.0);
        assertThat(coreSize("searchExecutor")).isEqualTo(3.0);
        assertThat(coreSize("publicLibraryExecutor")).isEqualTo(20.0);

        eLibraryExecutor.shutdown();
        searchExecutor.shutdown();
        publicLibraryExecutor.shutdown();
    }

    private double coreSize(String executorName) {
        return registry.get("executor.pool.core")
                .tag("name", executorName)
                .gauge()
                .value();
    }
}
