package com.checkbook.publiclibrary.link;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.StreamReadFeature;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern SESSION_NAME =
            Pattern.compile("csrf|token|jsessionid|signature|session|sessid|^sid$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRANCH_PARAM = Pattern.compile("[A-Za-z0-9_]{1,40}");
    private static final Pattern BRANCH_CODE = Pattern.compile("[A-Za-z0-9_.,-]{1,60}");
    /** 쿼리·프래그먼트 파라미터 이름이나 경로의 ;jsessionid= 에서 세션·토큰을 찾는다(PHPSESSID·ASPSESSIONID·sid 포함) */
    private static final Pattern SESSION_PARAM = Pattern.compile(
            "[?&;#/](?:sid|([^=&#;/?]*(?:csrf|token|jsessionid|signature|session|sessid)[^=&#;/?]*))=", Pattern.CASE_INSENSITIVE);

    /**
     * 형식 파일의 사이트 하나. flags는 운영 메모(예: robots 1회성 예외 — 운영자 요청 시 즉시 제거)라 응답에 싣지 않는다.
     * url·fields는 그 사이트의 통합검색이다. branch가 있으면 대응표의 도서관은 자기 지점만 검색한다.
     */
    public record Site(String host, String kind, String method, String url, Map<String, String> fields,
                       List<String> libCodes, String checkedAt, List<String> flags, Branch branch) {

        /**
         * 여러 도서관이 한 사이트를 쓸 때의 지점 대응표(11-04: 대표 도서관 지점이 주소에 박혀 다른 도서관이 엉뚱한 지점을
         * 검색하던 결함을 고치며 추가). url·fields는 지점 링크를 확인한 주소 틀이고, param 값을 기관 코드에 맞게 바꾼다.
         * 대응표에 없는 도서관은 사이트의 통합검색 주소를 쓴다.
         */
        public record Branch(String param, String url, Map<String, String> fields, Map<String, String> codes) {
        }
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
        Site.Branch branch = site.branch();
        String code = branch == null ? null : branch.codes().get(libCode);
        if ("POST".equals(site.method())) {
            Map<String, String> fields = new LinkedHashMap<>();
            (code == null ? site.fields() : branch.fields()).forEach((name, value) -> fields.put(name, TITLE.equals(value) ? title : value));
            if (code != null) {
                fields.put(branch.param(), code);
            }
            return new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.RESULT, "POST", site.url(), fields);
        }
        String template = code == null ? site.url() : withParam(branch.url(), branch.param(), code);
        return new PublicLibrarySearchLink(PublicLibrarySearchLink.Kind.RESULT, "GET", fill(template, title), null);
    }

    /** 주소 틀의 쿼리에서 param을 모두 빼고 param=value를 붙인다(프래그먼트는 그대로). value는 검사한 코드라 인코딩이 필요 없다 */
    static String withParam(String template, String param, String value) {
        int hash = template.indexOf('#');
        String head = hash < 0 ? template : template.substring(0, hash);
        String fragment = hash < 0 ? "" : template.substring(hash);
        int question = head.indexOf('?');
        String path = question < 0 ? head : head.substring(0, question);
        StringBuilder query = new StringBuilder();
        if (question >= 0) {
            for (String pair : head.substring(question + 1).split("&")) {
                if (pair.isEmpty() || pair.equals(param) || pair.startsWith(param + "=")) {
                    continue;
                }
                query.append(query.isEmpty() ? "" : "&").append(pair);
            }
        }
        query.append(query.isEmpty() ? "" : "&").append(param).append('=').append(value);
        return path + "?" + query + fragment;
    }

    /**
     * 주소의 검색어 자리를 채운다. 공백은 %20으로 넣는다 — '+'를 공백으로 읽지 않는 서버가 있다(11-03 파주: 목록 없이 틀만 옴).
     * 자리가 경로에 있으면 '/'를, 프래그먼트에 있으면 '|'를 공백으로 바꾼다. 경로의 %2F는 Tomcat·nginx가 기본으로
     * 거부하고, 프래그먼트 검색(#/search/ex?all=1|k|a|제목)은 '|'를 구분자로 쓴다. 쿼리 안에서는 둘 다 그대로 인코딩한다
     */
    static String fill(String template, String title) {
        int fragment = template.indexOf('#');
        int query = template.indexOf('?');
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (int at = template.indexOf(TITLE); at >= 0; at = template.indexOf(TITLE, from)) {
            String value = title;
            if (fragment >= 0 && at > fragment) {
                value = title.replace('|', ' ');
            } else if (query < 0 || at < query) {
                value = title.replace('/', ' ');
            }
            out.append(template, from, at).append(UriUtils.encode(value, StandardCharsets.UTF_8));
            from = at + TITLE.length();
        }
        return out.append(template.substring(from)).toString();
    }

    int size() {
        return byLibCode.size();
    }

    private static Document read(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return parse(jsonMapper, in);
        } catch (IOException e) {
            throw new IllegalStateException("도서관 검색 링크 형식 파일을 읽지 못했다: " + RESOURCE, e);
        }
    }

    /** 같은 키가 두 번 나오면 실패한다 — 기본은 뒤의 값이 조용히 덮어 지점 코드 오타를 놓친다(11-04 리뷰) */
    static Document parse(JsonMapper jsonMapper, InputStream in) {
        return jsonMapper.readerFor(Document.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).readValue(in);
    }

    private static void validateBranch(Site site, String where) {
        Site.Branch branch = site.branch();
        if (branch == null) {
            return;
        }
        if (branch.param() == null || !BRANCH_PARAM.matcher(branch.param()).matches() || branch.codes() == null || branch.codes().isEmpty()) {
            throw new IllegalStateException(where + "지점 대응표의 파라미터 이름이나 코드가 없다");
        }
        // 한 코드가 여러 기관에 붙으면 이름 대조가 틀렸을 가능성이 크다(예: '중앙도서관'과 '중앙도서관 분관 ○○도서관', 11-04 리뷰)
        Set<String> seen = new java.util.HashSet<>();
        for (String code : branch.codes().values()) {
            if (!seen.add(code)) {
                throw new IllegalStateException(where + "지점 코드 " + code + "가 두 기관 이상에 붙어 있다");
            }
        }
        String branchHost = URI.create(("POST".equals(site.method()) ? site.url() : branch.url()).replace(TITLE, "x")).getHost();
        String siteHost = URI.create(site.url().replace(TITLE, "x")).getHost();
        if (branchHost == null || !branchHost.equalsIgnoreCase(siteHost)) {
            throw new IllegalStateException(where + "지점 주소 호스트(" + branchHost + ")가 통합검색 주소 호스트와 다르다");
        }
        branch.codes().forEach((libCode, code) -> {
            if (!site.libCodes().contains(libCode)) {
                throw new IllegalStateException(where + "지점 대응표의 기관 " + libCode + "이(가) 사이트 기관 목록에 없다");
            }
            if (code == null || !BRANCH_CODE.matcher(code).matches()) {
                throw new IllegalStateException(where + "지점 코드가 이상하다: " + code);
            }
        });
        // 지점 주소 틀도 통합검색 주소와 같은 규칙으로 검사한다
        validate(new Site(site.host(), site.kind(), site.method(), "POST".equals(site.method()) ? site.url() : branch.url(),
                "POST".equals(site.method()) ? branch.fields() : null, site.libCodes(), site.checkedAt(), site.flags(), null));
    }

    private static void validate(Site site) {
        String where = "도서관 검색 링크 형식 오류(" + site.host() + "): ";
        if (site.libCodes() == null || site.libCodes().isEmpty()) {
            throw new IllegalStateException(where + "기관 코드가 없다");
        }
        if (site.url() == null) {
            throw new IllegalStateException(where + "주소가 없다");
        }
        // 조사할 때의 세션·보안 토큰이 남으면 다른 사용자에게는 깨진 링크가 되고 조사자 세션 값이 배포된다(11-04 리뷰: 동작 CSRFToken)
        Matcher leaked = SESSION_PARAM.matcher(site.url());
        if (leaked.find()) {
            throw new IllegalStateException(where + "세션·토큰 파라미터가 남아 있다: " + leaked.group());
        }
        if (site.fields() != null && site.fields().keySet().stream().anyMatch(name -> SESSION_NAME.matcher(name).find())) {
            throw new IllegalStateException(where + "POST 필드에 세션·토큰 값이 남아 있다");
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
        validateBranch(site, where);
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
