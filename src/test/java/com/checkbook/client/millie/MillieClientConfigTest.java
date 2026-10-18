package com.checkbook.client.millie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 밀리 경로는 설정으로 뺐다. 10-18에 상세 페이지가 v3 → v4로 바뀌었는데 v3도 200(빈 껍데기)을 줘서
 * 상태 코드로는 잡히지 않았다. 운영 설정 파일의 값이 실제로 바인딩되는지와, 비면 기동이 멈추는지를 확인한다.
 */
class MillieClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MillieClientConfig.class);

    // main의 application.yaml을 읽는다. src/test/resources에 application.yaml을 두면 그 파일이 대신 읽혀 이 확인이 무의미해진다
    @Test
    void bindsMillieSettingsFromApplicationYaml() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    MillieProperties properties = context.getBean(MillieProperties.class);
                    assertThat(properties.baseUrl()).isEqualTo("https://live-api.millie.co.kr");
                    assertThat(properties.timeout()).isEqualTo(2000);
                    assertThat(properties.searchPath()).isEqualTo("/v3/search/total");
                    assertThat(properties.detailUrlBase()).isEqualTo("https://www.millie.co.kr/v4/book/");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "https://www.millie.co.kr/v4/book", "www.millie.co.kr/v4/book/"})
    void failsToStartWhenDetailUrlBaseIsInvalid(String detailUrlBase) {
        assertStartupFailure("millie.search-path=/v3/search/total", "millie.detail-url-base=" + detailUrlBase,
                "detailUrlBase");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "v3/search/total", "/v3/search/total?x=1", "/v3/{x}"})
    void failsToStartWhenSearchPathIsInvalid(String searchPath) {
        assertStartupFailure("millie.search-path=" + searchPath, "millie.detail-url-base=https://www.millie.co.kr/v4/book/",
                "searchPath");
    }

    private void assertStartupFailure(String searchPath, String detailUrlBase, String field) {
        runner.withPropertyValues("millie.base-url=https://live-api.millie.co.kr", "millie.timeout=2000",
                        searchPath, detailUrlBase)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(field);
                });
    }
}
