package com.checkbook.support;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 시험 환경이 맞게 섰는지 확인한다(재현 시험이 아니다). 이 클래스가 뜬다는 것 자체가
 * Hibernate 검사(ddl-auto: validate) 통과 — 엔티티와 Flyway가 만든 표가 맞는다는 뜻이다.
 */
class PostgresEnvironmentTest extends PostgresIntegrationTest {

    // 운영(deploy/compose.yaml)과 같은 PostgreSQL에서 도는가
    @Test
    void runsOnTheSamePostgresVersionAsProduction() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(version).startsWith("18.6");
    }

    // H2 시험은 Flyway를 끄고 Hibernate가 표를 만들어 마이그레이션 SQL이 한 번도 안 돌았다 — 여기서는 전부 돈다
    @Test
    void appliesEveryFlywayMigration() throws Exception {
        int files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql").length;
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).hasSize(files);
        assertThat(applied).first().isEqualTo("1");
    }

    // A 재현의 전제: 같은 (isbn13, lib_code)를 막는 유니크 제약이 실제 표에 있다
    @Test
    void snapshotTableHasTheUniqueConstraint() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'uq_las_isbn13_lib_code' AND contype = 'u'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }
}
