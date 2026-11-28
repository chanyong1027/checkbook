package com.checkbook.elibrary.migration;

import com.checkbook.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V11(전자도서관 6곳 다시 열기 + 광주·전남 이름 6곳). Flyway는 시작할 때 빈 표에 이미 적용했으므로(목록 행은 시드로 손으로 넣음)
 * 운영 DB의 11-28 값을 넣고 같은 SQL 파일을 다시 돌려 본다.
 */
class ElibraryFixMigrationV11Test extends PostgresIntegrationTest {

    private static final String SCRIPT = "db/migration/V11__fix_elibrary_addresses_and_names.sql";
    private static final Timestamp BEFORE = Timestamp.valueOf("2026-10-20 00:00:00");

    @BeforeEach
    @AfterEach
    void clean() {
        truncate("elibrary");
    }

    // 운영 DB의 지금 값(11-28 조회)과 같으면 6곳이 열리고, 6곳 이름이 바뀌고, 다른 것은 그대로다
    @Test
    void fixesRowsThatStillHaveTheirProductionValues() {
        insert(249, "해군잠수함사령부", "https://rsfc.dkyobobook.co.kr", "INACTIVE", false);
        insert(1010, "부산연천초등학교", "https://ebook-yonchon.dkyobobook.co.kr", "INACTIVE", false);
        insert(1505, "천안용암초등학교", "https://yongam.dkyobobook.co.kr", "INACTIVE", false);
        insert(1761, "동명대학교", "https://libebook.tu.ac.kr:446", "INACTIVE", false);
        insert(1875, "전북대학교", "https://library.jbnu.ac.kr:444", "INACTIVE", false);
        insert(1906, "평택대학교", "http://ebook.ptu.ac.kr:8091", "INACTIVE", false);
        insert(500, "광주광역시 서구 통합전자도서관(구독)", "http://seogulib.dkyobobook.co.kr", "ACTIVE", true);
        insert(501, "광주광역시교육청 통합도서관", "http://libebook.gen.go.kr:88", "ACTIVE", false);
        insert(502, "광주광역시립도서관", "http://ebook.gwangju.go.kr:88", "INACTIVE", false);
        insert(503, "광주광역시북구통합도서관", "http://ebook.bukgu.gwangju.kr", "ACTIVE", false);
        insert(637, "전남도립도서관(구독)", "http://libjeonnam.dkyobobook.co.kr", "ACTIVE", true);
        insert(638, "전라남도교육청통합도서관", "http://ebook.jne.go.kr:8300", "INACTIVE", false);
        // 대상이 아닌 행: 1757(ACTIVE)과 같은 사이트라 열지 않는 1758, 중계 후보(INACTIVE)
        insert(1757, "덕성여자대학교", "http://duksunguniv.dkyobobook.co.kr", "ACTIVE", true);
        insert(1758, "덕성여자대학교(구독)", "https://duksunguniv.dkyobobook.co.kr", "INACTIVE", false);
        insert(1943, "다른 대학교", "http://ebook.example.ac.kr:8080", "INACTIVE", false);
        List<Map<String, Object>> untouchedBefore = rows(1757, 1758, 1943);

        runScript();

        assertThat(row(249)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", true)
                .containsEntry("base_url", "https://rsfc.dkyobobook.co.kr");
        assertThat(row(1010)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", true)
                .containsEntry("base_url", "https://ebook-yonchon.dkyobobook.co.kr");
        assertThat(row(1505)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", true)
                .containsEntry("base_url", "https://yongam.dkyobobook.co.kr");
        assertThat(row(1761)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", false)
                .containsEntry("base_url", "https://libebook.tu.ac.kr:446/elibrary-front");
        assertThat(row(1875)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", false)
                .containsEntry("base_url", "https://library.jbnu.ac.kr:444/elibrary-front");
        assertThat(row(1906)).containsEntry("status", "ACTIVE").containsEntry("robots_disallowed", false)
                .containsEntry("base_url", "https://ebook.ptu.ac.kr:446/elibrary-front");

        assertRenamed(500, "전남광주통합특별시 서구 통합도서관(구독)", "http://seogulib.dkyobobook.co.kr", "ACTIVE", true);
        assertRenamed(501, "전남광주통합특별시교육청 광주통합도서관", "http://libebook.gen.go.kr:88", "ACTIVE", false);
        assertRenamed(502, "전남광주통합특별시광주도서관", "http://ebook.gwangju.go.kr:88", "INACTIVE", false);
        assertRenamed(503, "전남광주통합특별시 북구 통합도서관", "http://ebook.bukgu.gwangju.kr", "ACTIVE", false);
        assertRenamed(637, "전남광주통합특별시전남도서관(구독)", "http://libjeonnam.dkyobobook.co.kr", "ACTIVE", true);
        assertRenamed(638, "전남광주통합특별시교육청 전남도서관", "http://ebook.jne.go.kr:8300", "INACTIVE", false);

        // 바꾼 12곳은 수정 시각이 새로 찍히고, 대상이 아닌 행은 수정 시각까지 그대로다
        List<Timestamp> changedAt = jdbcTemplate.queryForList(
                "SELECT updated_at FROM elibrary WHERE id NOT IN (1757, 1758, 1943)", Timestamp.class);
        assertThat(changedAt).hasSize(12).allSatisfy(at -> assertThat(at).isAfter(BEFORE));
        assertThat(rows(1757, 1758, 1943)).isEqualTo(untouchedBefore);
    }

    // 그 사이 손으로 고쳐 값이 달라진 행은 건드리지 않는다(이미 열었거나, 주소나 이름을 바꾼 경우)
    @Test
    void leavesRowsWhoseValuesChangedSince() {
        insert(249, "해군잠수함사령부", "https://rsfc.dkyobobook.co.kr", "ACTIVE", false);
        insert(1761, "동명대학교", "https://libebook.tu.ac.kr:446/other", "INACTIVE", false);
        insert(1906, "평택대학교", "http://ebook.ptu.ac.kr:8091", "DEAD", false);
        insert(500, "직접 고친 이름", "http://seogulib.dkyobobook.co.kr", "ACTIVE", true);
        // 같은 주소라도 id가 다르면 다른 행이다
        insert(2000, "다른 행", "https://yongam.dkyobobook.co.kr", "INACTIVE", false);
        List<Map<String, Object>> before = rows(249, 1761, 1906, 500, 2000);

        runScript();

        assertThat(rows(249, 1761, 1906, 500, 2000)).isEqualTo(before);
    }

    private void insert(long id, String name, String baseUrl, String status, boolean robotsDisallowed) {
        jdbcTemplate.update("""
                INSERT INTO elibrary (id, name, vendor_type, base_url, region, status, login_required,
                                      robots_disallowed, created_at, updated_at)
                VALUES (?, ?, 'KYOBO', ?, NULL, ?, FALSE, ?, ?, ?)
                """, id, name, baseUrl, status, robotsDisallowed, BEFORE, BEFORE);
    }

    private void runScript() {
        jdbcTemplate.execute((Connection connection) -> {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
            return null;
        });
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT name, base_url, status, robots_disallowed FROM elibrary WHERE id = ?", id);
    }

    private List<Map<String, Object>> rows(long... ids) {
        String in = String.join(", ", Arrays.stream(ids).mapToObj(String::valueOf).toList());
        return jdbcTemplate.queryForList("SELECT * FROM elibrary WHERE id IN (" + in + ") ORDER BY id");
    }

    private void assertRenamed(long id, String name, String baseUrl, String status, boolean robotsDisallowed) {
        assertThat(row(id)).containsEntry("name", name).containsEntry("base_url", baseUrl)
                .containsEntry("status", status).containsEntry("robots_disallowed", robotsDisallowed);
    }
}
