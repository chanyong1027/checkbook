package com.checkbook.client;

import com.checkbook.client.datanaru.DatanaruClientConfig;
import com.checkbook.client.kakao.KakaoBookClientConfig;
import com.checkbook.client.yes24.Yes24Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 API 키가 비어 있으면 기동을 멈춘다(빠른 실패). 설정 실수를 첫 사용자 요청까지 숨기지 않고,
 * 블루·그린 배포에서는 "새 칸이 안 뜨고 옛 칸 유지"로 끝나게 한다(OCI 이전 결정 카드 6, Q1).
 */
class ExternalApiKeyValidationTest {

    // Yes24ClientConfig는 RestClient·RateLimiter 빈까지 요구해서 설정 레코드만 따로 등록한다
    @Configuration
    @EnableConfigurationProperties(Yes24Properties.class)
    static class Yes24PropertiesOnly {
    }

    private final ApplicationContextRunner kakao = new ApplicationContextRunner()
            .withUserConfiguration(KakaoBookClientConfig.class)
            .withPropertyValues("kakao.book.base-url=https://dapi.kakao.com", "kakao.book.timeout=1000");

    private final ApplicationContextRunner datanaru = new ApplicationContextRunner()
            .withUserConfiguration(DatanaruClientConfig.class)
            .withPropertyValues("datanaru.base-url=https://data4library.kr/api",
                    "datanaru.timeout=2000", "datanaru.list-timeout=90000");

    private final ApplicationContextRunner yes24 = new ApplicationContextRunner()
            .withUserConfiguration(Yes24PropertiesOnly.class)
            .withPropertyValues("yes24.base-url=https://apis.yes24.com", "yes24.connect-timeout=200ms",
                    "yes24.read-timeout=700ms", "yes24.list-read-timeout=3s");

    @Test
    void kakaoFailsToStartWhenKeyIsBlank() {
        kakao.withPropertyValues("kakao.book.rest-api-key=  ")
                .run(context -> assertBlankKeyFailure(context.getStartupFailure(), "restApiKey"));
    }

    @Test
    void datanaruFailsToStartWhenKeyIsBlank() {
        datanaru.withPropertyValues("datanaru.auth-key=")
                .run(context -> assertBlankKeyFailure(context.getStartupFailure(), "authKey"));
    }

    @Test
    void yes24FailsToStartWhenKeyIsBlank() {
        yes24.withPropertyValues("yes24.api-key=")
                .run(context -> assertBlankKeyFailure(context.getStartupFailure(), "apiKey"));
    }

    @Test
    void startsWhenAllKeysArePresent() {
        kakao.withPropertyValues("kakao.book.rest-api-key=k").run(context -> assertThat(context).hasNotFailed());
        datanaru.withPropertyValues("datanaru.auth-key=d").run(context -> assertThat(context).hasNotFailed());
        yes24.withPropertyValues("yes24.api-key=y").run(context -> assertThat(context).hasNotFailed());
    }

    // 서버 .env에 줄 자체가 없을 때: yaml의 ${X:}가 빈 문자열을 넣어 @NotBlank가 잡는다.
    // 빈 기본값을 지운 ${X}는 @ConfigurationProperties 바인딩에서 해석 못 한 placeholder가 글자 그대로("${X}") 들어가
    // @NotBlank를 통과한다(@Value와 다름) — 그래서 빈 기본값을 남긴다
    @Test
    void failsToStartWhenEnvVarIsMissing() {
        yes24.withPropertyValues("yes24.api-key=${CHECKBOOK_TEST_UNSET_KEY:}")
                .run(context -> assertBlankKeyFailure(context.getStartupFailure(), "apiKey"));
    }

    // 위 주석의 함정을 고정해 둔다: 빈 기본값이 없으면 env가 없어도 기동해 버린다
    @Test
    void withoutEmptyDefaultUnresolvedPlaceholderPassesValidation() {
        yes24.withPropertyValues("yes24.api-key=${CHECKBOOK_TEST_UNSET_KEY}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Yes24Properties.class).apiKey())
                            .isEqualTo("${CHECKBOOK_TEST_UNSET_KEY}");
                });
    }

    // 그래서 운영 yaml의 세 키는 빈 기본값을 유지해야 한다(누가 ':'를 지우면 여기서 깨진다)
    @Test
    void applicationYamlKeepsEmptyDefaultsForKeys() throws IOException {
        String yaml = new ClassPathResource("application.yaml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(yaml)
                .contains("rest-api-key: ${KAKAO_BOOK_REST_API_KEY:}")
                .contains("auth-key: ${DATANARU_AUTH_KEY:}")
                .contains("api-key: ${YES24_KEY:}");
    }

    private static void assertBlankKeyFailure(Throwable failure, String field) {
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof BindValidationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(BindValidationException.class);
        assertThat(cause.getMessage()).contains(field);
    }
}
