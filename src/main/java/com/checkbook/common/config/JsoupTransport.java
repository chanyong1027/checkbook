package com.checkbook.common.config;

/**
 * Jsoup의 HTTP 전송을 HttpURLConnection(1.20까지의 기본)으로 고정한다(결정 2026-11-03 jsoup 업그레이드 카드).
 * <p>
 * jsoup 1.21.1부터 기본이 JDK HttpClient(HTTP/2)로 바뀌었다. 그대로 두면 응답 헤더 전 시간 초과가
 * {@code HttpTimeoutException}(SocketTimeoutException 아님)으로 와 전자도서관 실패 분류(시간 초과 → 접속 실패)와
 * robots 확인의 접속 불가 판정이 어긋나고, http 사이트에 h2c 업그레이드 헤더가 붙는다. 사이트당 5초 마감·지연 분포도
 * 옛 전송으로 잰 값이다. jsoup은 이 속성을 요청마다 읽으므로 앱 시작 때 한 번 정하면 된다.
 * HttpClient로 옮길 때는 분류를 고치고 사이트 응답을 다시 잰 뒤 이 설정을 지운다.
 * 시험은 Gradle이 같은 속성을 준다(build.gradle). IDE에서 Gradle을 거치지 않고 돌리면 HttpClient로 돌아
 * 시간 초과 시험이 깨질 수 있다 — IDE 실행 설정에 -Djsoup.useHttpClient=false를 넣는다.
 */
public final class JsoupTransport {

    public static final String PROPERTY = "jsoup.useHttpClient";

    private JsoupTransport() {
    }

    /** 이미 명시적으로 정했으면(-D) 그 값을 따른다 */
    public static void useLegacyHttpUrlConnection() {
        if (System.getProperty(PROPERTY) == null) {
            System.setProperty(PROPERTY, "false");
        }
    }
}
