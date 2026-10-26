package com.checkbook.support;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 PostgreSQL로 도는 통합 시험의 기본 클래스. 상속하면 postgres 태그가 붙어 기본 {@code test}에서 빠지고
 * {@code postgresTest}에서만 돈다(Docker 필요).
 * <ul>
 *   <li>프로필 test + postgres: test의 더미 키·작은 풀은 그대로 쓰고, DB 관련만 postgres가 덮는다
 *       (application-postgres.yml — Flyway V1~V8을 운영과 같은 SQL로 적용, Hibernate는 스키마 검사만)</li>
 *   <li>컨테이너는 시험끼리 함께 쓰므로 남긴 데이터가 다음 시험에 보인다 — 시험마다 {@link #truncate}로 정리한다</li>
 *   <li>트랜잭션을 직접 열고 닫는 시험(두 트랜잭션 순서 고정 등)을 위해 {@link #transactionTemplate}을 열어 둔다</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", "postgres"})
@Import(PostgresTestcontainersConfiguration.class)
@Tag("postgres")
public abstract class PostgresIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    /** 표를 비운다. 시퀀스(id)도 처음으로 — 시험이 순서에 기대지 않게 */
    protected void truncate(String... tables) {
        jdbcTemplate.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY");
    }
}
