package com.checkbook.elibrary.service;

import com.checkbook.elibrary.dto.KyoboELibrarySeedCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KyoboELibrarySeedCollectorTest {

    private final KyoboELibrarySeedCollector collector = new KyoboELibrarySeedCollector();

    @Test
    void parseCollectsAllAnchorsFromTabContSections() {
        Document document = Jsoup.parse("""
                <html>
                <body>
                    <div class="tab_cont on">
                        <ul>
                            <li><a href="http://a.dkyobobook.co.kr">기관 A</a></li>
                        </ul>
                    </div>
                    <div class="tab_cont">
                        <ul>
                            <li><a href="http://b.dkyobobook.co.kr">기관 B</a></li>
                        </ul>
                    </div>
                </body>
                </html>
                """);

        var result = collector.parse(document);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("기관 A");
        assertThat(result.get(0).baseUrl()).isEqualTo("http://a.dkyobobook.co.kr");
        assertThat(result.get(0).region()).isNull();
    }

    @Test
    void renderInsertSqlKeepsKyoboVendorAndNullRegion() {
        Document document = Jsoup.parse("""
                <html>
                <body>
                    <div class="tab_cont on">
                        <ul>
                            <li><a href="http://sample.dkyobobook.co.kr">샘플 도서관</a></li>
                        </ul>
                    </div>
                </body>
                </html>
                """);

        String sql = collector.renderInsertSql(collector.parse(document));

        assertThat(sql).contains("INSERT INTO elibrary");
        assertThat(sql).contains("'KYOBO'");
        assertThat(sql).contains("'http://sample.dkyobobook.co.kr'");
        assertThat(sql).contains("NULL");
    }

    @Test
    void 다시_돌려도_기존_행의_status는_덮어쓰지_않는다() {
        // 운영에서 INACTIVE·DEAD로 정리한 사이트가 시드를 다시 돌리면 ACTIVE로 되살아난다(10-20 전수 조사)
        String sql = collector.renderInsertSql(List.of(
                new KyoboELibrarySeedCandidate("샘플 도서관", "http://sample.dkyobobook.co.kr", null, "x")));

        assertThat(sql).contains("'ACTIVE'"); // 새 행에는 넣는다
        assertThat(sql).doesNotContain("status = EXCLUDED.status");
    }

    @Test
    void scheme이_두_번_붙은_주소는_하나로_줄인다() {
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://https://rsfc.dkyobobook.co.kr"))
                .isEqualTo("https://rsfc.dkyobobook.co.kr");
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://https://elib.gyeongnam.go.kr:8114"))
                .isEqualTo("https://elib.gyeongnam.go.kr:8114");
    }

    @Test
    void 끝의_슬래시는_뗀다() {
        // 클라이언트가 base + "/search/..."를 만들어 '//search'가 된다
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://https://ggwf.gg.go.kr/"))
                .isEqualTo("https://ggwf.gg.go.kr");
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl(" http://lib.example.ac.kr/elibrary-front/ "))
                .isEqualTo("http://lib.example.ac.kr/elibrary-front");
    }

    @Test
    void 주소가_아닌_값은_버린다() {
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://독립기념관")).isNull();
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://ㅁ")).isNull();
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://1")).isNull();
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://충청남도교육청 통합전자도서관")).isNull();
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://heo@n-online.jp")).isNull(); // 메일 주소
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("javascript:void(0)")).isNull();
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("")).isNull();
        // IP 주소는 주소다
        assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl("http://211.252.121.111"))
                .isEqualTo("http://211.252.121.111");
    }

    @Test
    void 목록에서_주소가_아닌_항목은_후보에서_뺀다() {
        Document document = Jsoup.parse("""
                <div class="tab_cont on"><ul>
                    <li><a href="http://https://a.dkyobobook.co.kr/">기관 A</a></li>
                    <li><a href="http://기관B">기관 B</a></li>
                </ul></div>
                """);

        var result = collector.parse(document);

        assertThat(result).extracting(KyoboELibrarySeedCandidate::baseUrl)
                .containsExactly("https://a.dkyobobook.co.kr");
    }

    @Test
    void 저장소의_시드_SQL은_정리된_주소만_담고_status를_덮어쓰지_않는다() throws IOException {
        // 옛 시드를 다시 돌리면 이중 표기 주소가 새 ACTIVE 행으로 들어가고(UNIQUE가 다른 문자열로 봄), 정리한 status도 되돌린다
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/sql/seed-elibrary-kyobo.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("'KYOBO', '([^']*)'").matcher(sql);
        int rows = 0;
        while (m.find()) {
            rows++;
            assertThat(KyoboELibrarySeedCollector.normalizeBaseUrl(m.group(1))).isEqualTo(m.group(1));
        }

        assertThat(rows).isGreaterThan(1900);
        assertThat(sql).doesNotContain("status = EXCLUDED.status");
    }
}
