package com.checkbook.elibrary.client;

import com.checkbook.elibrary.client.ELibraryClientException.Kind;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jsoup.HttpStatusException;
import org.jsoup.UnsupportedMimeTypeException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 크롤러 실패 종류 분류. 알림(업체 구조 변경)이 이 분류를 그대로 믿으므로
 * '사이트 하나가 죽음'(접속·HTTP·시간 초과)과 '업체 템플릿이 바뀜'(구조 변경·파싱 실패)을 섞지 않아야 한다.
 */
class ELibraryClientFailureKindTest {

    static Stream<ELibClient> clients() {
        return Stream.of(new KyoboELibClient(), new BookcubeELibClient());
    }

    @ParameterizedTest
    @MethodSource("clients")
    void 접속할_수_없으면_접속_실패(ELibClient client) {
        assertThatThrownBy(() -> client.search("http://127.0.0.1:1", "자바"))
                .isInstanceOfSatisfying(ELibraryClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.CONNECTION_FAILED));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void 결과_목록도_빈_결과_문구도_없으면_구조_변경(ELibClient client) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(html("<div class=\"unexpected\"></div>"));
            server.start();

            assertThatThrownBy(() -> client.search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.STRUCTURE_CHANGED));
        }
    }

    @Test
    void 교보_책_항목은_있는데_하나도_못_읽으면_파싱_실패() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(html("<ul class=\"book_resultList\"><li><span>제목 없음</span></li></ul>"));
            server.start();

            assertThatThrownBy(() -> new KyoboELibClient().search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.PARSE_FAILED));
        }
    }

    @Test
    void 북큐브_책_항목은_있는데_하나도_못_읽으면_파싱_실패() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(html("<ul class=\"list typelist\"><li class=\"item\"><span>제목 없음</span></li></ul>"));
            server.start();

            assertThatThrownBy(() -> new BookcubeELibClient().search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.PARSE_FAILED));
        }
    }

    @ParameterizedTest
    @MethodSource("clients")
    void 서버_오류_응답이면_HTTP_오류(ELibClient client) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500));
            server.start();

            assertThatThrownBy(() -> client.search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.HTTP_ERROR));
        }
    }

    // 업체가 검색 주소를 바꾸면 모든 사이트가 404 — 사이트 하나의 서버 오류와 나눠야 업체 단위 변경으로 볼 수 있다
    @Test
    void 교보_모든_경로가_404면_검색_주소_없음() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.enqueue(new MockResponse().setResponseCode(404));
            server.start();

            assertThatThrownBy(() -> new KyoboELibClient().search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.ENDPOINT_NOT_FOUND));
        }
    }

    @Test
    void 북큐브_404면_검색_주소_없음() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404));
            server.start();

            assertThatThrownBy(() -> new BookcubeELibClient().search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.ENDPOINT_NOT_FOUND));
        }
    }

    // 검색 결과가 HTML이 아니게 바뀐 것(JSON 등)은 응답 형식 변경 — 구조 변경으로 본다
    @ParameterizedTest
    @MethodSource("clients")
    void HTML이_아닌_응답이면_구조_변경(ELibClient client) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
            server.start();

            assertThatThrownBy(() -> client.search(base(server), "자바"))
                    .isInstanceOfSatisfying(ELibraryClientException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.STRUCTURE_CHANGED));
        }
    }

    // Jsoup 시간 초과는 15초로 고정이라 서버로 재현하지 않고 분류 규칙만 확인한다
    @Test
    void 입출력_예외_종류로_분류한다() {
        assertThat(ELibraryClientException.ofIo("x", new SocketTimeoutException()).kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(ELibraryClientException.ofIo("x", new HttpStatusException("x", 503, "u")).kind()).isEqualTo(Kind.HTTP_ERROR);
        assertThat(ELibraryClientException.ofIo("x", new HttpStatusException("x", 404, "u")).kind()).isEqualTo(Kind.ENDPOINT_NOT_FOUND);
        assertThat(ELibraryClientException.ofIo("x", new UnsupportedMimeTypeException("x", "application/json", "u")).kind())
                .isEqualTo(Kind.STRUCTURE_CHANGED);
        assertThat(ELibraryClientException.ofIo("x", new ConnectException()).kind()).isEqualTo(Kind.CONNECTION_FAILED);
        assertThat(ELibraryClientException.ofIo("x", new UnknownHostException()).kind()).isEqualTo(Kind.CONNECTION_FAILED);
        assertThat(ELibraryClientException.ofIo("x", new IOException()).kind()).isEqualTo(Kind.CONNECTION_FAILED);
    }

    private static MockResponse html(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><body>" + body + "</body></html>");
    }

    private static String base(MockWebServer server) {
        return server.url("/").toString().replaceAll("/$", "");
    }
}
