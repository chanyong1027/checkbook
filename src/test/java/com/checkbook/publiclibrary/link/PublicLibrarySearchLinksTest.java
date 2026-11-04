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
        return new Site("lib.example.kr", "RESULT", "GET", url, null, List.of(codes), "2026-11-04", List.of());
    }

    private static Site post(Map<String, String> fields, String... codes) {
        return new Site("post.example.kr", "RESULT", "POST", "https://post.example.kr/search.do", fields, List.of(codes),
                "2026-11-04", List.of());
    }

    private static Site searchPage(String url, String... codes) {
        return new Site("page.example.kr", "SEARCH_PAGE", "GET", url, null, List.of(codes), "2026-11-04", List.of());
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
    void 같은_기관이_두_사이트에_있으면_기동을_멈춘다() {
        assertThatThrownBy(() -> new PublicLibrarySearchLinks(List.of(
                get("https://lib.example.kr/r.do?kwd={title}", "111111"),
                searchPage("https://page.example.kr/search.do", "111111"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("111111");
    }

    @Test
    void 운영_형식_파일은_검증을_통과하고_조사한_기관_수를_담는다() {
        var links = new PublicLibrarySearchLinks(JsonMapper.builder().build());

        assertThat(links.size()).isGreaterThanOrEqualTo(1300);
    }
}
