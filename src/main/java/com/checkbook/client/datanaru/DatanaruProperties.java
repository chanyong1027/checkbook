package com.checkbook.client.datanaru;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 정보나루 설정. 키는 .env/운영 서버 .env의 DATANARU_AUTH_KEY에서만 온다(로그·응답에 남기지 않는다).
 * <ul>
 *   <li>timeout: bookExist·libSrch의 연결·읽기 상한(ms)</li>
 *   <li>listTimeout: loanItemSrch(인기 대출 목록 배치) 전용 상한(ms)</li>
 * </ul>
 */
@Validated
@ConfigurationProperties("datanaru")
public record DatanaruProperties(
        @NotBlank String baseUrl,
        String authKey,
        @Positive int timeout,
        @Positive int listTimeout
) {

    // 레코드 기본 toString은 모든 필드를 찍는다 — 키가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "DatanaruProperties[baseUrl=" + baseUrl
                + ", authKey=" + (authKey == null || authKey.isBlank() ? "(empty)" : "****")
                + ", timeout=" + timeout + ", listTimeout=" + listTimeout + "]";
    }
}
