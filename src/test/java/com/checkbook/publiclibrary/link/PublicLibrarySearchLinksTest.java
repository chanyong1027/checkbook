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
                "https://lib.example.kr/r.do?searchLibraryArr=MB&kwd={title}#list", null, Map.of("111111", "ME")), "111111", "222222")));

        assertThat(links.linkFor("111111", "흰").url()).isEqualTo("https://lib.example.kr/r.do?kwd=%ED%9D%B0&searchLibraryArr=ME#list");
    }

    @Test
    void 대응표에_없는_도서관은_통합검색_주소를_쓴다_다른_지점으로_보내지_않는다() {
        var links = new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?searchLibraryArr=MB&kwd={title}", null, Map.of("111111", "ME")), "111111", "222222")));

        assertThat(links.linkFor("222222", "흰").url()).isEqualTo("https://lib.example.kr/r.do?searchLibrary=ALL&kwd=%ED%9D%B0");
    }

    @Test
    void POST_사이트는_지점_필드를_바꾼다() {
        var site = new Site("post.example.kr", "RESULT", "POST", "https://post.example.kr/s.do", Map.of("q", "{title}", "lib", ""),
                List.of("111111"), "2026-11-04", List.of(),
                new Site.Branch("manage_code", null, Map.of("q", "{title}", "manage_code", "AA"), Map.of("111111", "MD")));

        assertThat(new PublicLibrarySearchLinks(List.of(site)).linkFor("111111", "흰").fields())
                .containsExactlyInAnyOrderEntriesOf(Map.of("q", "흰", "manage_code", "MD"));
    }

    @Test
    void 대응표의_기관이_사이트에_없거나_코드가_이상하면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("999999", "ME")), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("999999");
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("111111", "ME&x=1")), "111111"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 한_사이트에서_같은_지점_코드가_두_기관에_붙으면_기동을_멈춘다_이름_대조_오매칭_방지() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://lib.example.kr/r.do?kwd={title}", null, Map.of("111111", "MA", "222222", "MA")), "111111", "222222"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MA");
    }

    @Test
    void 지점_주소가_통합검색과_다른_호스트면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(branchSite(new Site.Branch("searchLibraryArr",
                "https://other.example.kr/r.do?kwd={title}", null, Map.of("111111", "MA")), "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("other.example.kr");
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
}
