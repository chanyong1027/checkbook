package com.checkbook.client.millie;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 밀리 설정. 키가 없는 공개 검색 API라 가릴 값은 없다.
 * <ul>
 *   <li>timeout: 검색 API 연결·읽기 상한(ms)</li>
 *   <li>searchPath: 검색 API 경로(baseUrl 기준)</li>
 *   <li>detailUrlBase: 사용자에게 주는 상세 페이지 주소 앞부분. 뒤에 book_seq를 붙인다</li>
 * </ul>
 * 경로는 밀리가 버전을 바꾸면 설정만 고쳐 재배포하려고 뺐다(10-18 상세 페이지 v3 → v4). 옛 주소도 200 빈 페이지를 줘서
 * 상태 코드로는 바뀐 걸 알 수 없다. 같은 이유로 형식도 검사한다 — 끝의 '/'가 빠진 상세 주소(…/bookSEQ)도 기동되고 빈 페이지가 될 수 있다.
 */
@Validated
@ConfigurationProperties("millie")
public record MillieProperties(
        @NotBlank String baseUrl,
        @Positive int timeout,
        // URI 템플릿 앞에 그대로 붙는다 — '?'·'{'가 섞이면 쿼리가 깨지거나 템플릿 변수로 읽힌다
        @NotBlank @Pattern(regexp = "^/[^?{}]*$") String searchPath,
        @NotBlank @Pattern(regexp = "^https://.+/$") String detailUrlBase
) {
}
