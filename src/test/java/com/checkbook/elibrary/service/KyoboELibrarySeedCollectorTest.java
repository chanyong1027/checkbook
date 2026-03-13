package com.checkbook.elibrary.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

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
}
