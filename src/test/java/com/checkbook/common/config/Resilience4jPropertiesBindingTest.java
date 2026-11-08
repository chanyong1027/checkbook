package com.checkbook.common.config;

import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import io.github.resilience4j.springboot.ratelimiter.autoconfigure.RateLimiterProperties;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resilience4j 설정 키가 라이브러리 속성과 맞는지 지킨다. Spring은 모르는 키를 오류 없이 무시해서, 이름이 틀리면
 * 설정이 조용히 기본값으로 돈다(2026-11-08 T5 재현에서 발견: permitted-calls-in-half-open-state는 없는 속성이라
 * 반열림 시험 호출이 3이 아니라 기본값 10이었다). 모르는 키를 허용하지 않고 묶어 이름 오류를 시험에서 잡는다.
 * 실제 컨텍스트로 묶인 값은 Yes24ClientWiringTest가 본다
 */
class Resilience4jPropertiesBindingTest {

    /** 우리가 쓰는 묶음. 새 묶음(timelimiter·bulkhead 등)을 쓰면 여기와 아래 bind에 함께 더한다 */
    private static final List<String> KNOWN_GROUPS = List.of(
            "resilience4j.circuitbreaker.", "resilience4j.retry.", "resilience4j.ratelimiter.");

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application-test.yml"})
    void 설정_파일의_resilience4j_키는_모두_라이브러리_속성이다(String file) throws IOException {
        List<PropertySource<?>> sources = load(file);

        // 묶음 이름이 틀리면(resilience4j.retries 등) 아래 bind는 '결과 없음'으로 끝나 오류가 안 난다 — 이름부터 본다
        List<String> keys = sources.stream()
                .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
                .filter(key -> key.startsWith("resilience4j."))
                .toList();
        assertThat(keys).isNotEmpty()
                .allSatisfy(key -> assertThat(KNOWN_GROUPS).anySatisfy(group -> assertThat(key).startsWith(group)));

        // 묶음 안의 모르는 키는 bind가 예외를 던진다
        Binder binder = binderFor(sources);
        BindHandler strict = new NoUnboundElementsBindHandler(BindHandler.DEFAULT);
        binder.bind("resilience4j.circuitbreaker", Bindable.of(CircuitBreakerProperties.class), strict);
        binder.bind("resilience4j.retry", Bindable.of(RetryProperties.class), strict);
        binder.bind("resilience4j.ratelimiter", Bindable.of(RateLimiterProperties.class), strict);
    }

    @Test
    void 운영_서킷은_반열림에서_시험_호출을_3번만_보낸다() throws IOException {
        CircuitBreakerProperties properties = binderFor(load("application.yaml"))
                .bind("resilience4j.circuitbreaker", Bindable.of(CircuitBreakerProperties.class))
                .get();

        for (String name : List.of("yes24", "millie", "datanaru")) {
            assertThat(properties.getInstances().get(name).getPermittedNumberOfCallsInHalfOpenState())
                    .as("%s 반열림 시험 호출 수", name)
                    .isEqualTo(3);
        }
    }

    private static List<PropertySource<?>> load(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    }

    private static Binder binderFor(List<PropertySource<?>> sources) {
        // ${X:기본값}을 풀어 앱과 같은 값으로 묶는다(풀지 않으면 Duration 변환 같은 엉뚱한 곳에서 실패한다)
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }
}
