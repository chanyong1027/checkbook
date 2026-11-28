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
 * V12(전자도서관 상태·주소 정정, 11-28 전수 확인). Flyway는 시작할 때 빈 표에 이미 적용했으므로(목록 행은 시드로 손으로 넣음)
 * 운영 DB의 11-28 값을 넣고 같은 SQL 파일을 다시 돌려 본다.
 */
class ElibraryFixMigrationV12Test extends PostgresIntegrationTest {

    private static final String SCRIPT = "db/migration/V12__fix_elibrary_status_and_addresses.sql";
    private static final Timestamp BEFORE = Timestamp.valueOf("2026-10-20 00:00:00");

    /** 같은 주소로 다시 여는 곳: id, 주소, 지금 상태 */
    private static final List<Object[]> REOPEN = List.of(
            new Object[]{532L, "http://lib.dangjin.go.kr:8080", "INACTIVE"},
            new Object[]{1740L, "http://ebook.nambu.ac.kr:8101", "INACTIVE"},
            new Object[]{1852L, "http://ebook.yewon.ac.kr:88", "INACTIVE"},
            new Object[]{1674L, "http://ebook.cku.ac.kr", "DEAD"});

    /** 새 주소로 여는 곳(지금 DEAD): id, 옛 주소, 새 주소 */
    private static final List<Object[]> MOVED = List.of(
            new Object[]{463L, "http://ebook.gangnam.go.kr", "http://ebook.gangnam.go.kr:9001"},
            new Object[]{480L, "http://www.gb.go.kr", "http://www.gb.go.kr:8085"},
            new Object[]{563L, "http://eblib.seosan.go.kr", "http://eblib.seosan.go.kr:8070"},
            new Object[]{601L, "http://nm.yeoncheon.go.kr:8091", "http://nm.yeoncheon.go.kr"},
            new Object[]{614L, "http://elibrary.ulsan.go.kr", "http://elibrary.ulsan.go.kr:8091"},
            new Object[]{631L, "http://ebook.icdonggu.go.kr:8091", "http://ebook.jemulpo.go.kr:8091"},
            new Object[]{632L, "http://ebook.michuhollib.go.kr", "http://ebook-incheonlib.mlss.co.kr"},
            new Object[]{648L, "http://ebook.mlss.co.kr:8100", "http://ebook-kidslib.mlss.co.kr"},
            new Object[]{681L, "http://ebook.ihl.kr:8097", "http://e-lib.ice.go.kr:8097"},
            new Object[]{1660L, "http://ebook.u1.ac.kr", "http://ebook.u1.ac.kr:9090"},
            new Object[]{1686L, "http://kyobo-seoul.konkuk.ac.kr", "http://kyobo-seoul.konkuk.ac.kr:8083"},
            new Object[]{1692L, "http://ebook.kyonggi.ac.kr:8001", "http://ebook.kyonggi.ac.kr"},
            new Object[]{1713L, "http://ebook.aa.af.mil", "http://ebook-afa.mlss.co.kr"},
            new Object[]{1755L, "http://ebook.kbtus.ac.kr:8090", "http://ebook-kbtus.mlss.co.kr"},
            new Object[]{1759L, "http://ebook1.dongguk.edu", "http://ebook1.dongguk.edu:8081"},
            new Object[]{1839L, "http://ebook.shinhan.ac.kr", "http://ebook.shinhan.ac.kr:8090"},
            new Object[]{1873L, "http://ebook.dorip.ac.kr:88", "http://dyebook.mokpo.ac.kr:88"},
            new Object[]{1890L, "http://elib.ck.ac.kr", "http://elib.ck.ac.kr:8091"},
            new Object[]{1901L, "http://ebook.cbpc.ac.kr:8091", "http://ebook.cpu.ac.kr:8091"},
            new Object[]{1920L, "http://elib.knuh.ac.kr", "http://elib.knuh.ac.kr:8082"});

    /** 포트가 바뀐 노출 중인 곳(robots 막힘 → 허용): id, 옛 주소, 새 주소 */
    private static final List<Object[]> PORT = List.of(
            new Object[]{1727L, "http://scnulib.scnu.ac.kr:8000", "http://scnulib.scnu.ac.kr:88"},
            new Object[]{1931L, "http://ebook.hansei.ac.kr:8089", "http://ebook.hansei.ac.kr:8087"});

    /** 숨기는 곳(지금 ACTIVE): id, 주소 */
    private static final List<Object[]> HIDE = List.of(
            new Object[]{496L, "http://ebk.gwangsan.go.kr"},
            new Object[]{515L, "http://ebook.gunsan.go.kr:8001"},
            new Object[]{672L, "http://ebook.ptlib.go.kr"},
            new Object[]{1673L, "http://ebook.gachon.ac.kr:8080"},
            new Object[]{1682L, "http://ebook.kangwon.ac.kr"},
            new Object[]{1729L, "http://ebook.gc.ac.kr:88/elibrary-front"},
            new Object[]{1794L, "http://kyoboelib.smuc.ac.kr:8091"},
            new Object[]{1885L, "http://julisebook.joongbu.ac.kr:8089"},
            new Object[]{607L, "http://www.ysplib.go.kr:9444"},
            new Object[]{664L, "http://e-lib.cbe.go.kr:8092"},
            new Object[]{1784L, "http://ebook.cup.ac.kr:88"},
            new Object[]{1774L, "http://ebook.mokwon.ac.kr:8091"},
            new Object[]{1781L, "http://ebook.baewha.ac.kr:8091"},
            new Object[]{1854L, "http://ebook.wsu.ac.kr"},
            new Object[]{1975L, "http://www.textbook.ac"});

    @BeforeEach
    @AfterEach
    void clean() {
        truncate("elibrary");
    }

    // 운영 DB의 지금 값(11-28)과 같으면 41곳이 바뀌고, 다른 것은 수정 시각까지 그대로다
    @Test
    void fixesRowsThatStillHaveTheirProductionValues() {
        insertProductionRows();
        // 대상이 아닌 행: 숨기지 않기로 한 곳(543 마포구 — 운영에서 검색됨), 같은 서버 다른 포트(485), 계속 숨긴 곳
        insert(543, "마포구청", "http://ebook.mapo.go.kr:8088", "ACTIVE", false);
        insert(485, "같은 서버 다른 포트", "http://e-lib.ice.go.kr:8092", "ACTIVE", false);
        insert(502, "계속 숨긴 곳", "http://ebook.gwangju.go.kr:88", "INACTIVE", false);
        List<Map<String, Object>> untouchedBefore = rows(543, 485, 502);

        runScript();

        for (Object[] r : REOPEN) {
            assertThat(row((long) r[0])).as("다시 열기 %s", r[0])
                    .containsEntry("status", "ACTIVE").containsEntry("base_url", r[1])
                    .containsEntry("robots_disallowed", false);
        }
        for (Object[] r : MOVED) {
            assertThat(row((long) r[0])).as("새 주소 %s", r[0])
                    .containsEntry("status", "ACTIVE").containsEntry("base_url", r[2])
                    .containsEntry("robots_disallowed", false);
        }
        for (Object[] r : PORT) {
            assertThat(row((long) r[0])).as("포트 %s", r[0])
                    .containsEntry("status", "ACTIVE").containsEntry("base_url", r[2])
                    .containsEntry("robots_disallowed", false);
        }
        for (Object[] r : HIDE) {
            assertThat(row((long) r[0])).as("숨김 %s", r[0])
                    .containsEntry("status", "INACTIVE").containsEntry("base_url", r[1])
                    .containsEntry("robots_disallowed", false);
        }

        List<Timestamp> changedAt = jdbcTemplate.queryForList(
                "SELECT updated_at FROM elibrary WHERE id NOT IN (543, 485, 502)", Timestamp.class);
        assertThat(changedAt).hasSize(41).allSatisfy(at -> assertThat(at).isAfter(BEFORE));
        assertThat(rows(543, 485, 502)).isEqualTo(untouchedBefore);
    }

    // 그 사이 손으로 고쳐 값이 달라진 행은 건드리지 않는다(이미 열었거나, 주소나 상태를 바꾼 경우)
    @Test
    void leavesRowsWhoseValuesChangedSince() {
        insert(532, "당진시립도서관", "http://lib.dangjin.go.kr:8080", "ACTIVE", true);
        insert(463, "강남구청", "http://ebook.gangnam.go.kr", "INACTIVE", false);
        insert(1727, "국립순천대학교", "http://scnulib.scnu.ac.kr:8000", "INACTIVE", true);
        insert(496, "광산구통합도서관", "http://ebk.gwangsan.go.kr", "DEAD", false);
        insert(1673, "가천대학교 전자도서관", "http://ebook.gachon.ac.kr:9999", "ACTIVE", false);
        // robots 값을 그 사이 손으로 바꾼 행(다시 열기·옮기기는 지금 false, 포트는 지금 true일 때만 바꾼다)
        insert(1740, "남부대학교", "http://ebook.nambu.ac.kr:8101", "INACTIVE", true);
        insert(480, "경북도서관", "http://www.gb.go.kr", "DEAD", true);
        insert(1931, "한세대학교", "http://ebook.hansei.ac.kr:8089", "ACTIVE", false);
        // 같은 주소라도 id가 다르면 다른 행이다
        insert(2000, "다른 행", "http://ebook.cku.ac.kr", "DEAD", false);
        List<Map<String, Object>> before = rows(532, 463, 1727, 496, 1673, 1740, 480, 1931, 2000);

        runScript();

        assertThat(rows(532, 463, 1727, 496, 1673, 1740, 480, 1931, 2000)).isEqualTo(before);
    }

    // 새 주소를 이미 다른 행이 쓰고 있으면 그 행만 건너뛴다 — base_url은 유일해서, 그대로 바꾸면 마이그레이션이 실패해 배포가 멈춘다
    @Test
    void skipsMoveWhenAnotherRowAlreadyHasTheNewAddress() {
        insert(463, "강남구청", "http://ebook.gangnam.go.kr", "DEAD", false);
        insert(1931, "한세대학교", "http://ebook.hansei.ac.kr:8089", "ACTIVE", true);
        insert(3000, "새 주소를 먼저 쓴 행", "http://ebook.gangnam.go.kr:9001", "ACTIVE", false);
        insert(3001, "새 포트를 먼저 쓴 행", "http://ebook.hansei.ac.kr:8087", "INACTIVE", false);
        insert(480, "경북도서관", "http://www.gb.go.kr", "DEAD", false);
        List<Map<String, Object>> before = rows(463, 1931, 3000, 3001);

        runScript();

        assertThat(rows(463, 1931, 3000, 3001)).isEqualTo(before);
        // 같은 파일의 다른 행은 그대로 바뀐다
        assertThat(row(480)).containsEntry("status", "ACTIVE").containsEntry("base_url", "http://www.gb.go.kr:8085");
    }

    // 새 DB(목록 행 없음)에서는 아무것도 하지 않는다
    @Test
    void doesNothingOnAnEmptyTable() {
        runScript();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM elibrary", Long.class)).isZero();
    }

    private void insertProductionRows() {
        for (Object[] r : REOPEN) {
            insert((long) r[0], "다시 열 곳 " + r[0], (String) r[1], (String) r[2], false);
        }
        for (Object[] r : MOVED) {
            insert((long) r[0], "옮긴 곳 " + r[0], (String) r[1], "DEAD", false);
        }
        for (Object[] r : PORT) {
            insert((long) r[0], "포트 바뀐 곳 " + r[0], (String) r[1], "ACTIVE", true);
        }
        for (Object[] r : HIDE) {
            insert((long) r[0], "숨길 곳 " + r[0], (String) r[1], "ACTIVE", false);
        }
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
}
