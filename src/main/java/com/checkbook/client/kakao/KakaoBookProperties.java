package com.checkbook.client.kakao;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 카카오 도서 검색 설정. 키는 .env/운영 서버 .env의 KAKAO_BOOK_REST_API_KEY에서만 온다(로그·응답에 남기지 않는다).
 * <ul>
 *   <li>timeout: 연결·읽기 각각의 상한(ms). 값의 근거는 application.yaml 주석</li>
 * </ul>
 */
@Validated
@ConfigurationProperties("kakao.book")
public record KakaoBookProperties(
        @NotBlank String baseUrl,
        @NotBlank String restApiKey,
        @Positive int timeout
) {

    // 레코드 기본 toString은 모든 필드를 찍는다 — 키가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "KakaoBookProperties[baseUrl=" + baseUrl
                + ", restApiKey=" + (restApiKey == null || restApiKey.isBlank() ? "(empty)" : "****")
                + ", timeout=" + timeout + "]";
    }
}
