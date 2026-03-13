package com.checkbook.elibrary.service;

import com.checkbook.elibrary.dto.KyoboELibrarySeedCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class KyoboELibrarySeedCollector {

    public static final String SOURCE_URL = "https://ebook.kyobobook.co.kr/dig/cff/e-library";

    public List<KyoboELibrarySeedCandidate> collect() throws IOException {
        Document document = Jsoup.connect(SOURCE_URL)
                .timeout(15000)
                .get();

        return parse(document);
    }

    List<KyoboELibrarySeedCandidate> parse(Document document) {
        return document.select("div.tab_cont a[href]").stream()
                .map(this::toCandidate)
                .toList();
    }

    public String renderInsertSql(List<KyoboELibrarySeedCandidate> candidates) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("-- Generated from ")
                .append(SOURCE_URL)
                .append("\n");
        sqlBuilder.append("-- No URL validity filtering. region is left NULL intentionally.\n\n");

        for (KyoboELibrarySeedCandidate candidate : candidates) {
            sqlBuilder.append("INSERT INTO elibrary (name, vendor_type, base_url, region, status, login_required) VALUES (")
                    .append('\'').append(escapeSql(candidate.name())).append("', ")
                    .append("'KYOBO', ")
                    .append('\'').append(escapeSql(candidate.baseUrl())).append("', ")
                    .append("NULL, ")
                    .append("'ACTIVE', ")
                    .append("false")
                    .append(") ON CONFLICT (base_url) DO UPDATE SET ")
                    .append("name = EXCLUDED.name, ")
                    .append("region = EXCLUDED.region, ")
                    .append("status = EXCLUDED.status, ")
                    .append("login_required = EXCLUDED.login_required;")
                    .append('\n');
        }

        return sqlBuilder.toString();
    }

    private KyoboELibrarySeedCandidate toCandidate(Element anchor) {
        return new KyoboELibrarySeedCandidate(
                anchor.text().trim(),
                anchor.attr("href").trim(),
                null,
                SOURCE_URL
        );
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }
}
