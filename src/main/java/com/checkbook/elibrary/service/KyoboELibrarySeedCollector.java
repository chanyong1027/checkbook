package com.checkbook.elibrary.service;

import com.checkbook.elibrary.dto.KyoboELibrarySeedCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public class KyoboELibrarySeedCollector {

    public static final String SOURCE_URL = "https://ebook.kyobobook.co.kr/dig/cff/e-library";

    // 교보 목록 href에는 'http://https://…'처럼 scheme이 두 번 붙은 값이 있다(10-20 전수 조사 18곳)
    private static final Pattern DOUBLED_SCHEME = Pattern.compile("^(?i)(?:https?://)+(?=https?://)");
    // ASCII 호스트 이름이나 IPv4. '독립기념관'·'ㅁ'·'1'처럼 기관 이름이 들어간 값을 거른다
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$");

    public List<KyoboELibrarySeedCandidate> collect() throws IOException {
        Document document = Jsoup.connect(SOURCE_URL)
                .timeout(15000)
                .get();

        return parse(document);
    }

    List<KyoboELibrarySeedCandidate> parse(Document document) {
        return document.select("div.tab_cont a[href]").stream()
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .toList();
    }

    public String renderInsertSql(List<KyoboELibrarySeedCandidate> candidates) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append("-- Generated from ")
                .append(SOURCE_URL)
                .append("\n");
        sqlBuilder.append("-- base_url is normalized (doubled scheme, trailing slash); non-address hrefs are dropped. region is left NULL intentionally.\n");
        sqlBuilder.append("-- Existing rows keep their status (operators mark dead sites INACTIVE/DEAD).\n\n");

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
                    .append("login_required = EXCLUDED.login_required;")
                    .append('\n');
        }

        return sqlBuilder.toString();
    }

    private KyoboELibrarySeedCandidate toCandidate(Element anchor) {
        String baseUrl = normalizeBaseUrl(anchor.attr("href"));
        if (baseUrl == null) {
            return null;
        }
        return new KyoboELibrarySeedCandidate(
                anchor.text().trim(),
                baseUrl,
                null,
                SOURCE_URL
        );
    }

    /**
     * 목록 href를 앱이 부를 기본 주소로 다듬는다. 주소가 아니면 null.
     * 겹친 scheme은 뒤의 것을 쓰고, 끝 '/'는 뗀다(클라이언트가 base + "/search/..."를 만든다).
     * 호스트가 없거나 ASCII 이름·IPv4가 아니거나, 사용자 정보(메일 주소)·쿼리가 붙은 값은 버린다
     */
    static String normalizeBaseUrl(String href) {
        if (href == null) {
            return null;
        }
        String value = DOUBLED_SCHEME.matcher(href.trim()).replaceFirst("");
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        if (uri.getHost() == null || !HOST.matcher(uri.getHost()).matches()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        return value;
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }
}
