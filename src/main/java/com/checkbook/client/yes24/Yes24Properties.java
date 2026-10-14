package com.checkbook.client.yes24;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * YES24 OpenAPI 설정. 키는 .env/운영 비밀 저장소의 YES24_KEY에서만 온다(로그·응답에 남기지 않는다).
 * <ul>
 *   <li>readTimeout: 요청 전송부터 본문 수신 끝까지의 <b>전체 마감</b> (JdkClientHttpRequestFactory 의미). 식별 예산 700ms</li>
 *   <li>connectTimeout: 그 안에서 연결 단계만의 상한</li>
 * </ul>
 * 실제 지연 분포는 http.client.requests로 관측한다.
 */
@Validated
@ConfigurationProperties("yes24")
public record Yes24Properties(
        @NotBlank String baseUrl,
        String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {

    // 레코드 기본 toString은 모든 필드를 찍는다 — 키가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "Yes24Properties[baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "(empty)" : "****")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
