package com.checkbook.publiclibrary.link;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 도서관별 "사이트에서 검색" 링크 형식(2026-11-04 조사, 결정 `2026-11-04-딥링크-POST-자동제출-결정카드`).
 * <p>
 * 형식은 {@value #RESOURCE}에 있고 기동할 때 한 번 읽어 검사한다. 틀린 형식이 있으면 기동을 멈춘다 —
 * 잘못된 링크를 조용히 내보내는 것보다 배포 전에 알아채는 편이 낫다. 런타임에 도서관 사이트를 부르지 않는다.
 * 형식에 없는 도서관은 링크를 주지 않고 화면이 기존 홈페이지 링크를 쓴다.
 */
@Component
public class PublicLibrarySearchLinks {

    static final String RESOURCE = "public-library/search-links.json";
    private static final String TITLE = "{title}";
    private static final Set<String> SCHEMES = Set.of("http", "https");

    /** 형식 파일의 사이트 하나. flags는 운영 메모(예: robots 1회성 예외 — 운영자 요청 시 즉시 제거)라 응답에 싣지 않는다. */
    public record Site(String host, String kind, String method, String url, Map<String, String> fields,
                       List<String> libCodes, String checkedAt, List<String> flags) {
    }

    record Document(int version, String description, List<Site> sites) {
    }

    private final Map<String, Site> byLibCode;

    @Autowired
    public PublicLibrarySearchLinks(JsonMapper jsonMapper) {
        this(read(jsonMapper).sites());
    }

    /** 형식 목록을 직접 넘겨 만든다(시험·조립용). 검사 규칙은 파일을 읽을 때와 같다. */
    public PublicLibrarySearchLinks(List<Site> sites) {
        Map<String, Site> index = new HashMap<>();
        for (Site site : sites) {
            validate(site);
            for (String libCode : site.libCodes()) {
                Site previous = index.putIfAbsent(libCode, site);
                if (previous != null) {
                    throw new IllegalStateException("도서관 " + libCode + "이(가) 두 사이트에 있다: "
                            + previous.host() + ", " + site.host());
                }
            }
        }
        this.byLibCode = Map.copyOf(index);
    }

    /**
     * @param keyword 정규화한 검색어(부제·판 정보를 뗀 본제목). 비어 있으면 결과 링크를 만들지 않는다
     * @return 형식이 없거나 검색어가 없어 결과 링크를 못 만들면 null
     */
    public PublicLibrarySearchLink linkFor(String libCode, String keyword) {
        Site site = byLibCode.get(libCode);
        if (site == null) {
            return null;
        }
        if ("SEARCH_PAGE".equals(site.kind())) {
            return new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.SEARCH_PAGE, "GET", site.url(), null);
        }
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String title = keyword.strip();
        if ("POST".equals(site.method())) {
            Map<String, String> fields = new LinkedHashMap<>();
            site.fields().forEach((name, value) -> fields.put(name, TITLE.equals(value) ? title : value));
            return new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.RESULT, "POST", site.url(), fields);
        }
        // 공백은 %20으로 넣는다. '+'를 공백으로 읽지 않는 서버가 있다(11-03 파주: 목록 없이 틀만 옴)
        String encoded = UriUtils.encode(title, StandardCharsets.UTF_8);
        return new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.RESULT, "GET", site.url().replace(TITLE, encoded), null);
    }

    int size() {
        return byLibCode.size();
    }

    private static Document read(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return jsonMapper.readValue(in, Document.class);
        } catch (IOException e) {
            throw new IllegalStateException("도서관 검색 링크 형식 파일을 읽지 못했다: " + RESOURCE, e);
        }
    }

    private static void validate(Site site) {
        String where = "도서관 검색 링크 형식 오류(" + site.host() + "): ";
        if (site.libCodes() == null || site.libCodes().isEmpty()) {
            throw new IllegalStateException(where + "기관 코드가 없다");
        }
        if (site.url() == null) {
            throw new IllegalStateException(where + "주소가 없다");
        }
        URI uri;
        try {
            uri = URI.create(site.url().replace(TITLE, "x"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + "주소를 해석할 수 없다", e);
        }
        if (uri.getScheme() == null || !SCHEMES.contains(uri.getScheme().toLowerCase()) || uri.getHost() == null) {
            throw new IllegalStateException(where + "http/https 주소가 아니다");
        }
        switch (site.kind() + "/" + site.method()) {
            case "RESULT/GET" -> {
                if (!site.url().contains(TITLE)) {
                    throw new IllegalStateException(where + "결과 링크에 검색어 자리 " + TITLE + "가 없다");
                }
            }
            case "RESULT/POST" -> {
                if (site.fields() == null || !site.fields().containsValue(TITLE) || site.url().contains(TITLE)) {
                    throw new IllegalStateException(where + "POST 필드에 검색어 자리 " + TITLE + "가 없다");
                }
            }
            case "SEARCH_PAGE/GET" -> {
                if (site.url().contains(TITLE)) {
                    throw new IllegalStateException(where + "검색 화면 링크에는 검색어 자리가 없어야 한다");
                }
            }
            default -> throw new IllegalStateException(where + "모르는 종류/방식 " + site.kind() + "/" + site.method());
        }
    }
}
