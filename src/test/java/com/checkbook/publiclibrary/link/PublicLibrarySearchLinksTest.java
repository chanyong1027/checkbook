package com.checkbook.publiclibrary.link;

import com.checkbook.publiclibrary.link.PublicLibrarySearchLinks.Site;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicLibrarySearchLinksTest {

    // 구조만 흉내 낸 합성 형식이다(실제 도서관 주소가 아님)
    private static Site get(String url, String... codes) {
        return new Site("lib.example.kr", "RESULT", "GET", url, null, List.of(codes), "2026-11-04", List.of(), null);
    }

    private static Site post(Map<String, String> fields, String... codes) {
        return new Site("post.example.kr", "RESULT", "POST", "https://post.example.kr/search.do", fields, List.of(codes),
                "2026-11-04", List.of(), null);
    }

    private static Site searchPage(String url, String... codes) {
        return new Site("page.example.kr", "SEARCH_PAGE", "GET", url, null, List.of(codes), "2026-11-04", List.of(), null);
    }

    @Test
    void GET_결과_링크는_검색어를_퍼센트20_방식으로_인코딩해_넣는다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?kwd={title}&type=ALL", "111111")));

        PublicLibrarySearchLink link = links.linkFor("111111", "소년이 온다");

        assertThat(link.kind()).isEqualTo(PublicLibrarySearchLink.Kind.RESULT);
        assertThat(link.method()).isEqualTo("GET");
        // 공백을 '+'로 보내면 목록 없이 틀만 주는 서버가 있다(11-03 조사)
        assertThat(link.url()).isEqualTo("https://lib.example.kr/r.do?kwd=%EC%86%8C%EB%85%84%EC%9D%B4%20%EC%98%A8%EB%8B%A4&type=ALL");
        assertThat(link.fields()).isNull();
    }

    @Test
    void 검색어_자리가_두_곳이면_둘_다_채운다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?pre={title}&kwd={title}", "111111")));

        assertThat(links.linkFor("111111", "흰").url()).isEqualTo("https://lib.example.kr/r.do?pre=%ED%9D%B0&kwd=%ED%9D%B0");
    }

    @Test
    void 검색어_안의_예약_문자도_인코딩해_주소를_깨지_않는다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?kwd={title}", "111111")));

        assertThat(links.linkFor("111111", "a&b=c#d").url()).isEqualTo("https://lib.example.kr/r.do?kwd=a%26b%3Dc%23d");
    }

    @Test
    void 검색어_자리가_경로에_있으면_슬래시를_공백으로_바꾼다_서버가_퍼센트2F를_거부하지_않게() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/search/keyword/{title}?page=1", "111111")));

        assertThat(links.linkFor("111111", "24/7").url()).isEqualTo("https://lib.example.kr/search/keyword/24%207?page=1");
    }

    @Test
    void 검색어_자리가_프래그먼트에_있으면_구분자_파이프를_공백으로_바꾼다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/#/search/ex?all=1%7Ck%7Ca%7C{title}", "111111")));

        assertThat(links.linkFor("111111", "a|b").url()).isEqualTo("https://lib.example.kr/#/search/ex?all=1%7Ck%7Ca%7Ca%20b");
    }

    @Test
    void 쿼리의_슬래시와_파이프는_그대로_인코딩한다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?kwd={title}", "111111")));

        assertThat(links.linkFor("111111", "24/7|x").url()).isEqualTo("https://lib.example.kr/r.do?kwd=24%2F7%7Cx");
    }

    // --- 지점 대응표: 여러 도서관이 한 사이트를 쓰면 도서관마다 자기 지점을 검색한다 ---

    private static Site branchSite(Site.Branch branch, String... codes) {
        return new Site("lib.example.kr", "RESULT", "GET", "https://lib.example.kr/r.do?searchLibrary=ALL&kwd={title}", null,
                List.of(codes), "2026-11-04", List.of(), branch);
    }

    @Test
    void 지점_코드가_있는_도서관은_지점_주소에서_지점_값만_바꿔_검색한다() {
        var links = new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?searchLibraryArr=MB&kwd={title}#list", null, Map.of("111111", "ME"), UNBRANCHED_222222),
                "111111", "222222")));

        assertThat(links.linkFor("111111", "흰").url()).isEqualTo("https://lib.example.kr/r.do?kwd=%ED%9D%B0&searchLibraryArr=ME#list");
    }

    private static final Map<String, String> UNBRANCHED_222222 = Map.of("222222", "사이트 지점 목록에 없음");

    @Test
    void 통합으로_둔_도서관은_통합검색_주소를_쓴다_다른_지점으로_보내지_않는다() {
        var links = new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?searchLibraryArr=MB&kwd={title}", null, Map.of("111111", "ME"), UNBRANCHED_222222),
                "111111", "222222")));

        assertThat(links.linkFor("222222", "흰").url()).isEqualTo("https://lib.example.kr/r.do?searchLibrary=ALL&kwd=%ED%9D%B0");
    }

    @Test
    void POST_사이트는_지점_필드를_바꾼다() {
        var site = new Site("post.example.kr", "RESULT", "POST", "https://post.example.kr/s.do", Map.of("q", "{title}", "lib", ""),
                List.of("111111"), "2026-11-04", List.of(),
                new Site.Branch("manage_code", null, Map.of("q", "{title}", "manage_code", "AA"), Map.of("111111", "MD"), null));

        assertThat(new PublicLibrarySearchLinks(List.of(site)).linkFor("111111", "흰").fields())
                .containsExactlyInAnyOrderEntriesOf(Map.of("q", "흰", "manage_code", "MD"));
    }

    @Test
    void 대응표의_기관이_사이트에_없거나_코드가_이상하면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("999999", "ME"), null), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("999999");
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("111111", "ME&x=1"), null), "111111"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 한_사이트에서_같은_지점_코드가_두_기관에_붙으면_기동을_멈춘다_이름_대조_오매칭_방지() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("111111", "MA", "222222", "MA"), null), "111111", "222222"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MA");
    }

    @Test
    void 지점_주소가_통합검색과_다른_호스트면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://other.example.kr/r.do?kwd={title}", null, Map.of("111111", "MA"), null), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("other.example.kr");
    }

    // 11-24: 지점 코드가 이유 없이 빠진 기관(송파 글마루)이 조용히 통합으로 갔다 — 모든 기관을 지점 코드나 통합(이유 필수)으로 분류한다
    private static Site.Branch branch(Map<String, String> codes, Map<String, String> unbranched) {
        return new Site.Branch("searchLibraryArr", "https://lib.example.kr/r.do?kwd={title}", null, codes, unbranched);
    }

    @Test
    void 지점_코드에도_통합_목록에도_없는_기관이_있으면_기동을_멈춘다_빠뜨린_것이_조용히_통합으로_가지_않게() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(branch(Map.of("111111", "MA"), null), "111111", "222222"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("222222").hasMessageContaining("unbranched");
    }

    @Test
    void 지점_코드와_통합_양쪽에_있는_기관은_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(
                branch(Map.of("111111", "MA"), Map.of("111111", "이유")), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("양쪽");
    }

    @Test
    void 통합으로_둔_이유가_비었거나_사이트에_없는_기관이면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(
                branch(Map.of("111111", "MA"), Map.of("222222", " ")), "111111", "222222"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이유");
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(
                branch(Map.of("111111", "MA"), Map.of("999999", "이유")), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("999999");
    }

    private static final String DUP_CODES = "{\"version\":1,\"description\":\"x\",\"sites\":[{\"host\":\"lib.example.kr\","
            + "\"kind\":\"RESULT\",\"method\":\"GET\",\"url\":\"https://lib.example.kr/r.do?kwd={title}\",\"libCodes\":[\"111111\"],"
            + "\"branch\":{\"param\":\"searchLibraryArr\",\"url\":\"https://lib.example.kr/r.do?kwd={title}\","
            + "\"codes\":{\"111111\":\"MA\",\"111111\":\"MB\"}}}]}";

    private static java.io.InputStream utf8(String json) {
        return new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void 지점_코드표에_같은_기관이_두_번_있으면_읽기를_멈춘다_뒤의_값이_조용히_덮지_않게() {
        assertThatThrownBy(() -> PublicLibrarySearchLinks.parse(JsonMapper.builder().build(), utf8(DUP_CODES)))
                .hasMessageContaining("Duplicate");
    }

    @Test
    void 대조_엄격_모드가_없으면_뒤의_값이_조용히_이긴다() throws Exception {
        PublicLibrarySearchLinks.Document doc = JsonMapper.builder().build().readValue(utf8(DUP_CODES), PublicLibrarySearchLinks.Document.class);

        assertThat(doc.sites().get(0).branch().codes()).containsExactlyEntriesOf(Map.of("111111", "MB"));
    }

    @Test
    void POST_결과는_필드의_검색어_자리만_원문으로_채운다_인코딩은_브라우저가_한다() {
        var links = new PublicLibrarySearchLinks(List.of(post(Map.of("query", "{title}", "menu", "7"), "222222")));

        PublicLibrarySearchLink link = links.linkFor("222222", "소년이 온다");

        assertThat(link.method()).isEqualTo("POST");
        assertThat(link.url()).isEqualTo("https://post.example.kr/search.do");
        assertThat(link.fields()).containsExactlyInAnyOrderEntriesOf(Map.of("query", "소년이 온다", "menu", "7"));
    }

    @Test
    void 검색_화면_링크는_제목_없이도_준다() {
        var links = new PublicLibrarySearchLinks(List.of(searchPage("https://page.example.kr/search.do", "333333")));

        assertThat(links.linkFor("333333", null).kind()).isEqualTo(PublicLibrarySearchLink.Kind.SEARCH_PAGE);
        assertThat(links.linkFor("333333", "흰").url()).isEqualTo("https://page.example.kr/search.do");
    }

    @Test
    void 제목이_없거나_형식이_없는_도서관은_링크가_없다_화면은_홈페이지를_쓴다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?kwd={title}", "111111")));

        assertThat(links.linkFor("111111", null)).isNull();
        assertThat(links.linkFor("111111", "  ")).isNull();
        assertThat(links.linkFor("999999", "흰")).isNull();
    }

    @Test
    void 결과_링크에_검색어_자리가_없으면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?kwd=x", "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lib.example.kr");
    }

    @Test
    void POST에_검색어_자리가_없으면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(post(Map.of("query", "x"), "222222"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void http_https가_아닌_주소는_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(get("javascript:alert({title})", "111111"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 세션_토큰류_파라미터가_남은_주소는_기동을_멈춘다_조사자_세션_값이_배포되지_않게() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?CSRFToken=1d6c&kwd={title}", "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CSRFToken");
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do;jsessionid=AB12?kwd={title}", "111111"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(post(Map.of("query", "{title}", "_csrf", "x"), "222222"))))
                .isInstanceOf(IllegalStateException.class);
        for (String leaked : List.of("https://lib.example.kr/#token=ab&q={title}", "https://lib.example.kr/r.php?PHPSESSID=ab&q={title}",
                "https://lib.example.kr/r.do?sid=ab&q={title}", "https://lib.example.kr/r.asp?ASPSESSIONIDQA=ab&q={title}")) {
            assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(get(leaked, "111111")))).as(leaked)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 이름에_key가_들어간_정상_파라미터는_거부하지_않는다() {
        var links = new PublicLibrarySearchLinks(List.of(get("https://lib.example.kr/r.do?searchKey=ALL&bookKey=&sidebar=1&q={title}", "111111")));

        assertThat(links.linkFor("111111", "흰")).isNotNull();
    }

    @Test
    void 같은_기관이_두_사이트에_있으면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(
                get("https://lib.example.kr/r.do?kwd={title}", "111111"),
                searchPage("https://page.example.kr/search.do", "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("111111");
    }

    @Test
    void 운영_형식_파일은_검증을_통과하고_조사한_기관_수를_담는다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());

        assertThat(links.size()).isGreaterThanOrEqualTo(900);
    }

    /**
     * 11-24: 송파 사이트는 대표 도서관(거마) 홈페이지 경로 하나를 12곳이 같이 써서, 어느 도서관을 눌러도 페이지 이름이
     * "거마도서관"으로 떴다(코드 없는 4곳은 통합 결과까지). 도서관마다 자기 홈페이지 경로와 자기 지점 값으로 가야 한다.
     */
    @Test
    void 송파_도서관은_각자_자기_홈페이지_경로와_지점_값으로_검색한다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());
        // 기관 → 홈페이지 경로/간략검색 메뉴/지점 값(11-24 사이트에서 확인)
        Map<String, String> homepage = Map.ofEntries(
                Map.entry("111049", "spglib/menu/10467|MB"), Map.entry("111084", "sppnlib/menu/10795|BA"),
                Map.entry("111114", "sp2lib/menu/10903|BB"), Map.entry("111117", "spclib/menu/10243|MA"),
                Map.entry("111173", "sp3lib/menu/11012|BC"), Map.entry("111175", "sp4lib/menu/11123|BD"),
                Map.entry("111191", "spmlib/menu/11229|MC"), Map.entry("111259", "spelib/menu/10581|MD"),
                Map.entry("111435", "spjlib/menu/10113|ME"), Map.entry("111448", "spdlib/menu/10688|MF"),
                Map.entry("111456", "spalib/menu/11342|MG"), Map.entry("111472", "spwlib/menu/10360|MH"));

        homepage.forEach((libCode, expected) -> {
            String[] parts = expected.split("\\|");
            assertThat(links.linkFor(libCode, "싯다르타").url()).as(libCode).isEqualTo(
                    "https://www.splib.or.kr/" + parts[0] + "/program/30001/plusSearchResultList.do"
                            + "?searchType=SIMPLE&searchCategory=BOOK&searchKey=ALL"
                            + "&searchKeyword=%EC%8B%AF%EB%8B%A4%EB%A5%B4%ED%83%80&searchLibraryArr=" + parts[1]);
        });
    }

    /**
     * 11-25: 송파와 같은 종류 — 통합 주소가 대표 도서관 홈페이지 경로(강남 /dchlib/ 등)라 어느 도서관을 눌러도 그 도서관 이름
     * 페이지로 떴다. 첫 화면 검색창이 쓰는 공통 통합 경로가 있는 사이트는 모든 기관이 그 경로로 간다.
     */
    @Test
    void 공통_통합_경로가_있는_사이트는_모든_기관이_대표_도서관_경로가_아닌_통합_경로로_간다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());
        var doc = PublicLibrarySearchLinks.parse(JsonMapper.builder().build(),
                PublicLibrarySearchLinksTest.class.getClassLoader().getResourceAsStream(PublicLibrarySearchLinks.RESOURCE));
        Map<String, String> integratedPath = Map.of(
                "lib.pocheon.go.kr", "/intro/plusSearchResultList.do",
                "lib.yongin.go.kr", "/intro/plusSearchResultList.do",
                "library.gangnam.go.kr", "/intro/plusSearchResultList.do",
                "www.goyanglib.or.kr", "/center/program/searchResultList.do",
                "www.hscitylib.or.kr", "/intro/program/searchResultList.do",
                "www.ptlib.go.kr", "/intro/plusSearchResultList.do",
                "www.ydplib.or.kr", "/intro/plusSearchResultList.do",
                "www.l4d.or.kr", "/intro/plusSearchResultList.do");

        doc.sites().stream().filter(s -> integratedPath.containsKey(s.host())).forEach(s -> s.libCodes().forEach(libCode ->
                assertThat(java.net.URI.create(links.linkFor(libCode, "흰").url()).getPath())
                        .as(s.host() + " " + libCode).isEqualTo(integratedPath.get(s.host()))));
        assertThat(doc.sites().stream().filter(s -> integratedPath.containsKey(s.host())).count()).isEqualTo(8);
    }

    /**
     * 11-25: 공통 통합 경로가 없는 사이트(첫 화면 검색창부터 특정 도서관 사이트로 감)는 송파처럼 도서관마다 자기 홈페이지
     * 경로로 간다(파주·마포·부평·용산, 브라우저 확인에서 찾은 광진). 작은도서관·영어도서관은 그 사이트의 공용 사이트
     * (/libsmall/, /englib/, /small/)로, 홈페이지가 없는 파주
     * 넷은 기존 주소(/gclib/)에 남는다. 표는 기관 · 첫 경로 · 지점 값(-는 지점 값 없는 검색)이고 사이트에서 확인한 값이다.
     */
    @Test
    void 공통_통합_경로가_없는_사이트는_도서관마다_자기_사이트_경로와_지점_값으로_간다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());
        var doc = PublicLibrarySearchLinks.parse(JsonMapper.builder().build(),
                PublicLibrarySearchLinksTest.class.getClassLoader().getResourceAsStream(PublicLibrarySearchLinks.RESOURCE));
        String table = "141040 gclib MB,141041 mslib MC,141049 bwlib MD,141082 jalib MA,141156 ghlib MQ,141242 jslib ME,"
                + "141243 gtlib MS,141251 jrlib MF,141326 hslib MH,141397 gclib3 MK,141489 mplib ML,141517 hblib MI,"
                + "141544 grlib MM,141566 thlib MN,141602 pplib MP,141604 hulib MR,141614 wllib MO,141630 srlib MJ,"
                + "141267 gclib SA,141268 gclib SD,141299 gclib -,741292 gclib SF,"
                + "111086 sglib MA,111104 libsmall ME,111105 libsmall MF,111106 libsmall MG,111171 libsmall MH,111257 libsmall MB,"
                + "111258 libsmall MI,111374 libsmall MC,711140 libsmall MJ,111346 englib MK,111469 englib ML,111462 purme HK,"
                + "111467 mcl HQ,111514 naru MN,711596 metalib DO,"
                + "128007 miracle MA,128035 bugaech MB,128040 galsan MC,128056 samsan MD,128057 bugae ME,128085 chungcheon MF,"
                + "111120 cheongpa MB,111473 dream MA,711650 yongmaru OA,111364 small YN,111409 small YD,711023 small YG,"
                + "711396 small SA,711397 small YB,711398 small YC,711400 small YF,711401 small YI,711402 small YJ,"
                + "711403 small YK,711405 small YM,711466 small YS,711497 small YO,"
                + "111036 gjinfo MA,111115 jy4dong MC,111116 jgsports MD,111305 gu3dong MB,111495 jyriver MF,111521 gunja MG,"
                + "111523 achasan MH,111537 childeng MI";
        Map<String, String[]> expected = new java.util.HashMap<>();
        for (String row : table.split(",")) {
            String[] p = row.split(" ");
            expected.put(p[0], p);
        }

        expected.forEach((libCode, p) -> {
            String url = links.linkFor(libCode, "흰").url();
            assertThat(java.net.URI.create(url).getPath()).as(libCode).startsWith("/" + p[1] + "/");
            if ("-".equals(p[2])) {
                assertThat(url).as(libCode).doesNotContain("searchLibraryArr=");
            } else {
                assertThat(url).as(libCode).endsWith("&searchLibraryArr=" + p[2]);
            }
        });
        var hosts = java.util.Set.of("lib.paju.go.kr", "mplib.mapo.go.kr", "www.bppl.or.kr", "www.yslibrary.or.kr",
                "www.gwangjinlib.seoul.kr");
        assertThat(doc.sites().stream().filter(s -> hosts.contains(s.host())).flatMap(s -> s.libCodes().stream()))
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    /**
     * 11-25: 이름이 비슷하기만 해 통합으로 뒀던 기관 10곳을 확인해 지점으로 옮겼다(그 지점 결과의 책을 정보나루가 이 기관
     * 소장으로 답함). 통합으로 되돌아가면 다시 사이트 전체 결과가 뜬다.
     */
    @Test
    void 비슷한_이름으로_확인한_기관은_자기_지점_값으로_간다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());
        String table = "711266 library.gangnam.go.kr SB,141013 library.yeoncheon.go.kr MA,111116 www.gwangjinlib.seoul.kr MD,"
                + "141170 www.hscitylib.or.kr MF,141241 www.hscitylib.or.kr MH,141661 www.hscitylib.or.kr TB,"
                + "741680 www.hscitylib.or.kr MV,141578 www.hscitylib.or.kr MP,111053 www.l4d.or.kr MD,711400 www.yslibrary.or.kr YF";

        for (String row : table.split(",")) {
            String[] p = row.split(" ");
            String url = links.linkFor(p[0], "흰").url();
            assertThat(java.net.URI.create(url).getHost()).as(p[0]).isEqualTo(p[1]);
            assertThat(url).as(p[0]).endsWith("Arr=" + p[2]); // 화성은 searchManageCodeArr
        }
    }
}
