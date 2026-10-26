package com.checkbook.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 시험용 PostgreSQL 컨테이너. 운영(deploy/compose.yaml)과 같은 이미지를 쓴다 — PostgreSQL에서만 보이는 동작
 * (같은 키 INSERT 대기·23505·ON CONFLICT)을 재현하려는 것이라 버전까지 맞춘다(T4 결정 카드 10).
 * <p>
 * 빈으로 두면 스프링이 컨텍스트를 만들 때 컨테이너를 띄우고 닫을 때 끈다. 컨테이너는 컨텍스트마다 하나다 —
 * 설정이 같은 시험 클래스끼리만 컨텍스트(와 컨테이너)를 함께 쓰고, {@code @MockitoBean}·{@code @MockitoSpyBean} 구성이
 * 다르면 컨텍스트가 따로 생겨 컨테이너도 따로 뜬다(시간·메모리만 더 들고 결과는 같다 — 클래스끼리 DB가 섞이지 않는다).
 * {@link ServiceConnection}이 접속 정보(URL·계정)를 이 컨테이너 값으로 바꿔 끼운다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
    }
}
