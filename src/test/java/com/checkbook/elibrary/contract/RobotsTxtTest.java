package com.checkbook.elibrary.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 9309 판정. 전수 조사 스크립트(survey.py)가 리뷰에서 재현한 urllib.robotparser의 오판 사례를 그대로 옮겼다 */
class RobotsTxtTest {

    // "어린왕자"를 퍼센트 인코딩한 검색 주소(앱 클라이언트가 부르는 꼴)
    private static final String SEARCH =
            "/search/searchList.ink?schClst=all&schDvsn=000&schTxt=%EC%96%B4%EB%A6%B0%EC%99%95%EC%9E%90";

    private static boolean allowed(String robots) {
        return allowed(robots, SEARCH);
    }

    private static boolean allowed(String robots, String pathAndQuery) {
        return RobotsTxt.parse(robots).allows(pathAndQuery);
    }

    @Test
    void 와일드카드_확장자와_쿼리() {
        assertThat(allowed("User-agent: *\nDisallow: /*.ink")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /*?")).isFalse();
    }

    @Test
    void 순서가_아니라_가장_긴_규칙이_이긴다() {
        assertThat(allowed("User-agent: *\nAllow: /\nDisallow: /search")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /search\nAllow: /search/searchList.ink")).isTrue();
    }

    @Test
    void 길이가_같으면_Allow() {
        assertThat(allowed("User-agent: *\nDisallow: /search\nAllow: /search")).isTrue();
    }

    @Test
    void 끝_고정_달러() {
        assertThat(allowed("User-agent: *\nDisallow: /*.ink$")).isTrue(); // 쿼리가 붙어 끝이 .ink가 아님
        assertThat(allowed("User-agent: *\nDisallow: /*.ink$", "/search/searchList.ink")).isFalse();
    }

    @Test
    void 우리_UA_묶음이_있으면_별표_묶음은_보지_않는다() {
        // 앱은 브라우저 UA(Mozilla/5.0 …)를 보내므로 제품 토큰은 mozilla다
        assertThat(allowed("User-agent: Mozilla\nDisallow: /\n\nUser-agent: *\nAllow: /")).isFalse();
        assertThat(allowed("User-agent: Googlebot\nDisallow: /\n\nUser-agent: *\nAllow: /")).isTrue();
    }

    @Test
    void 같은_이름_묶음은_합치고_한_묶음에_UA가_여러_줄일_수_있다() {
        assertThat(allowed("User-agent: *\nAllow: /a\n\nUser-agent: *\nDisallow: /search")).isFalse();
        assertThat(allowed("User-agent: Googlebot\nUser-agent: *\nDisallow: /search")).isFalse();
    }

    @Test
    void 빈_Disallow와_주석과_UA_앞_규칙은_규칙이_아니다() {
        assertThat(allowed("Disallow: /\nUser-agent: *\nDisallow:\n# Disallow: /search")).isTrue();
    }

    @Test
    void robots_txt_자체는_늘_허용() {
        assertThat(allowed("User-agent: *\nDisallow: /", "/robots.txt")).isTrue();
    }

    @Test
    void 퍼센트_인코딩은_대소문자를_가리지_않는다() {
        assertThat(allowed("User-agent: *\nDisallow: /*%ec%96%b4")).isFalse();
    }

    @Test
    void 교보_호스팅_기본값은_전부_막는다() {
        // 전수 조사에서 1,408곳이 이 꼴이었다
        assertThat(allowed("User-agent: *\nDisallow: /")).isFalse();
    }

    @Test
    void 없음은_전부_허용() {
        assertThat(RobotsTxt.ALLOW_ALL.allows(SEARCH)).isTrue();
        assertThat(allowed("")).isTrue();
    }

    @Test
    void 비ASCII_글자는_퍼센트_인코딩해서_비교한다() {
        // RFC 9309 2.2.2 — 규칙을 한글 원문으로 적어도 앱이 부르는 인코딩된 주소를 막는다(CodeRabbit #54)
        assertThat(allowed("User-agent: *\nDisallow: /*schTxt=어린")).isFalse();
        // 거꾸로 대상 경로가 원문이어도(리다이렉트 Location) 인코딩된 규칙과 맞는다
        assertThat(allowed("User-agent: *\nDisallow: /도서/%ec%96%b4", "/도서/어린왕자")).isFalse();
        // 길이도 정규화한 문자열로 잰다: 인코딩하면 Allow(/검색 → 19자)가 Disallow(/%EA, 4자)보다 길다
        assertThat(allowed("User-agent: *\nDisallow: /%EA\nAllow: /검색", "/검색/목록")).isTrue();
    }

    @Test
    void 맨_앞_BOM은_지운다() {
        // 남아 있으면 첫 'User-agent'가 다른 키가 되어 첫 묶음이 사라지고 전부 허용으로 오판한다
        assertThat(allowed("\uFEFFUser-agent: *\nDisallow: /")).isFalse();
    }

    @Test
    void user_agent는_앞의_제품_토큰만_본다() {
        assertThat(allowed("User-agent: Mozilla/5.0\nDisallow: /\n\nUser-agent: *\nAllow: /")).isFalse();
    }

    @Test
    void 와일드카드_여러_개와_끝_고정() {
        assertThat(allowed("User-agent: *\nDisallow: /*search*.ink")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /*search*.ink$", "/search/searchList.ink")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /*list*$", "/search/searchList.ink")).isTrue(); // 대소문자를 가린다
        assertThat(allowed("User-agent: *\nDisallow: /search*")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /search$")).isTrue();
        assertThat(allowed("User-agent: *\nDisallow: /a*b*c", "/axxbyyc")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /a*b*c$", "/axxbyycz")).isTrue();
    }

    @Test
    void 와일드카드가_많은_규칙도_곧바로_끝난다() {
        // 정규식이면 역추적으로 오래 걸릴 꼴(남의 서버가 주는 값이라 막아 둔다)
        String rule = "/" + "*a".repeat(30) + "*b";
        long start = System.nanoTime();
        assertThat(allowed("User-agent: *\nDisallow: " + rule, "/" + "a".repeat(5_000))).isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(1));
    }

    @Test
    void 정규식_특수문자는_글자_그대로다() {
        assertThat(allowed("User-agent: *\nDisallow: /search/searchList.ink?schClst=all")).isFalse();
        assertThat(allowed("User-agent: *\nDisallow: /search/searchList(ink)")).isTrue();
    }
}
