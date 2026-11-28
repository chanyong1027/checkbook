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
 * V13(전자도서관 이름 31곳). Flyway는 시작할 때 빈 표에 이미 적용했으므로(목록 행은 시드로 손으로 넣음)
 * 운영 DB의 11-28 이름을 넣고 같은 SQL 파일을 다시 돌려 본다.
 */
class ElibraryFixMigrationV13Test extends PostgresIntegrationTest {

    private static final String SCRIPT = "db/migration/V13__fix_elibrary_names.sql";
    private static final Timestamp BEFORE = Timestamp.valueOf("2026-10-20 00:00:00");

    /** id, 운영의 지금 이름, 새 이름 */
    private static final List<Object[]> RENAMES = List.of(
            new Object[]{37L, "NCSOFT", "NC"},
            new Object[]{286L, "광주광역시 서구청", "전남광주통합특별시 서구청"},
            new Object[]{344L, "세종특별자치시평생교육학습관", "세종특별자치시교육청평생교육원"},
            new Object[]{364L, "전라북도 국제교류센터", "전북국제협력진흥원"},
            new Object[]{366L, "전라북도인재개발원", "전북특별자치도 인재개발원"},
            new Object[]{376L, "통계청 전자도서관", "국가데이터처"},
            new Object[]{463L, "강남구청", "강남구 전자도서관"},
            new Object[]{472L, "경기도립중앙도서관 평택분관", "경기평택교육도서관"},
            new Object[]{473L, "경기도립포천분관", "경기포천교육도서관"},
            new Object[]{478L, "경기평생학습관", "경기평생교육학습관"},
            new Object[]{525L, "나주시립도서관", "나주시립도서관(빛가람시립도서관)"},
            new Object[]{531L, "단양다누리도서관", "단양군립올누림도서관"},
            new Object[]{537L, "도봉문화정보도서관", "도봉구 전자책도서관"},
            new Object[]{546L, "만덕도서관", "부산 북구 구립도서관"},
            new Object[]{561L, "상록도서관", "전남광주통합특별시 서구 통합전자도서관"},
            new Object[]{568L, "서초구립반포도서관", "서초구 전자도서관"},
            new Object[]{570L, "선경도서관", "수원시 전자도서관"},
            new Object[]{598L, "여주시평생학습센터", "여주시립도서관"},
            new Object[]{609L, "옥천군공공도서관", "옥천군 공공도서관(가양도서관)"},
            new Object[]{618L, "월평도서관", "대전 서구 전자도서관"},
            new Object[]{621L, "의정부지식정보센터", "의정부시도서관"},
            new Object[]{631L, "인천송림도서관", "인천광역시 제물포구 구립도서관"},
            new Object[]{650L, "중구구립도서관", "서울중구 통합전자도서관"},
            new Object[]{1681L, "강릉원주대학교", "강원대학교 강릉캠퍼스"},
            new Object[]{1755L, "대전침례신학대학교", "한국침례신학대학교"},
            new Object[]{1782L, "백석대학교", "백석대학교(백석예술대학교)"},
            new Object[]{1873L, "전남도립대학교", "국립목포대학교(담양캠퍼스)"},
            new Object[]{1901L, "충북도립대학", "충북도립대학교"},
            new Object[]{1908L, "한경대학교", "한경국립대학교"},
            new Object[]{1918L, "한국예술종합대학교", "한국예술종합학교"},
            new Object[]{1920L, "한국전통문화학교", "한국전통문화대학교"});

    @BeforeEach
    @AfterEach
    void clean() {
        truncate("elibrary");
    }

    // 운영 이름과 같으면 31곳 이름이 바뀌고(상태·주소·robots 값은 그대로), 다른 행은 수정 시각까지 그대로다
    @Test
    void renamesRowsThatStillHaveTheirProductionNames() {
        for (Object[] r : RENAMES) {
            long id = (long) r[0];
            // 상태·robots 값을 섞어 넣는다 — 이름만 바뀌어야 한다
            insert(id, (String) r[1], "http://site-" + id + ".example.kr", id % 2 == 0 ? "ACTIVE" : "INACTIVE", id % 3 == 0);
        }
        // 대상이 아닌 행: 이름을 그대로 두기로 한 곳(261·1902·487), 이름은 같지만 id가 다른 행
        insert(261, "현대엘리베이터", "http://ebook-hri.mlss.co.kr", "ACTIVE", false);
        insert(1902, "충북보건과학대학교", "http://ebook.chsu.ac.kr:8080", "ACTIVE", false);
        insert(487, "고양시 도서관센터", "http://ebook.goyanglib.or.kr", "ACTIVE", false);
        insert(2000, "백석대학교", "http://other.example.kr", "ACTIVE", false);
        List<Map<String, Object>> untouchedBefore = rows(261, 1902, 487, 2000);
        List<Map<String, Object>> targetsBefore = withoutNameAndUpdatedAt(allRowsExcept(261, 1902, 487, 2000));

        runScript();

        // 대상 행도 이름과 수정 시각 말고는 어느 열도 바뀌지 않는다
        assertThat(withoutNameAndUpdatedAt(allRowsExcept(261, 1902, 487, 2000))).isEqualTo(targetsBefore);

        for (Object[] r : RENAMES) {
            long id = (long) r[0];
            assertThat(row(id)).as("이름 %s", id)
                    .containsEntry("name", r[2])
                    .containsEntry("base_url", "http://site-" + id + ".example.kr")
                    .containsEntry("status", id % 2 == 0 ? "ACTIVE" : "INACTIVE")
                    .containsEntry("robots_disallowed", id % 3 == 0);
        }
        List<Timestamp> changedAt = jdbcTemplate.queryForList(
                "SELECT updated_at FROM elibrary WHERE id NOT IN (261, 1902, 487, 2000)", Timestamp.class);
        assertThat(changedAt).hasSize(RENAMES.size()).allSatisfy(at -> assertThat(at).isAfter(BEFORE));
        assertThat(rows(261, 1902, 487, 2000)).isEqualTo(untouchedBefore);
    }

    // 그 사이 손으로 이름을 고친 행은 건드리지 않는다
    @Test
    void leavesRowsWhoseNameChangedSince() {
        insert(286, "직접 고친 이름", "http://ebook.seogu.gwangju.kr:88", "ACTIVE", false);
        insert(1681, "강원대학교", "http://ebook.gwnu.ac.kr:8085", "ACTIVE", false);
        insert(37, "NC", "http://ncsoft.dkyobobook.co.kr", "ACTIVE", true);
        List<Map<String, Object>> before = rows(286, 1681, 37);

        runScript();

        assertThat(rows(286, 1681, 37)).isEqualTo(before);
    }

    // 새 DB(목록 행 없음)에서는 아무것도 하지 않는다
    @Test
    void doesNothingOnAnEmptyTable() {
        runScript();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM elibrary", Long.class)).isZero();
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

    private List<Map<String, Object>> allRowsExcept(long... ids) {
        String in = String.join(", ", Arrays.stream(ids).mapToObj(String::valueOf).toList());
        return jdbcTemplate.queryForList("SELECT * FROM elibrary WHERE id NOT IN (" + in + ") ORDER BY id");
    }

    private static List<Map<String, Object>> withoutNameAndUpdatedAt(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(r);
            copy.remove("name");
            copy.remove("updated_at");
            return copy;
        }).toList();
    }

    private List<Map<String, Object>> rows(long... ids) {
        String in = String.join(", ", Arrays.stream(ids).mapToObj(String::valueOf).toList());
        return jdbcTemplate.queryForList("SELECT * FROM elibrary WHERE id IN (" + in + ") ORDER BY id");
    }
}
