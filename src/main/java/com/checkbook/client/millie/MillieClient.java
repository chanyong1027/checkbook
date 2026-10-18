package com.checkbook.client.millie;

import com.checkbook.client.millie.dto.MillieBookItem;
import com.checkbook.client.millie.dto.MillieSearchResponse;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Slf4j
@Component
public class MillieClient {

    private static final String USER_AGENT =
            "CheckBook/1.0 (+https://github.com/chanyong1027/checkbook)";

    private final RestClient restClient;
    private final ExternalCallBudget budget;
    private final String searchPath;

    public MillieClient(MillieProperties properties, ExternalCallBudget budget) {
        this.budget = budget;
        this.searchPath = properties.searchPath();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .requestFactory(factory)
                .build();
    }

    private static final int RESP_CD_OK = 200;

    /**
     * 제목으로 밀리 도서를 검색한다. 정상 0건은 빈 목록, 그 밖의 실패는 {@link MillieClientException}.
     * 이전에는 모든 실패를 빈 목록으로 삼켜 "밀리에 없음"과 장애가 구분되지 않았다.
     */
    public List<MillieBookItem> searchByTitle(String title) {
        if (title == null || title.isBlank()) return List.of();
        // 예산 소진은 MillieClientException(장애)과 구분해 그대로 던진다 — 섹션은 SKIPPED(DAILY_LIMIT)
        budget.acquire(ExternalProvider.MILLIE);

        MillieSearchResponse response;
        try {
            response = restClient.get()
                    // 제목은 템플릿 변수로 넘긴다: 그대로 넣으면 "{…}"가 템플릿으로 해석돼 예외가 나고, "+"가 공백으로 읽힌다
                    .uri(searchPath + "?searchType=total&keyword={keyword}"
                            + "&contentlimitCount=20&postlimitCount=0&librarylimitCount=0&startPage=1", title)
                    .retrieve()
                    .body(MillieSearchResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("밀리 검색 HTTP 오류: title={}, status={}", title, e.getStatusCode().value());
            throw new MillieClientException("밀리 HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // 밀리 장애 중에는 검색마다 실패하므로 스택은 debug로만 남긴다
            log.warn("밀리 검색 실패: title={}, cause={}", title, rootCause(e).toString());
            log.debug("밀리 검색 실패 상세", e);
            throw new MillieClientException("밀리 호출 실패", e);
        }

        // 실제 0건 응답도 RESP_CD 200 + RESP_DATA.content.list([])를 모두 갖는다(10-15 실호출 확인).
        // 구조가 빠졌거나 코드가 다르면 '없음'이 아니라 실패다
        if (response == null) {
            throw new MillieClientException("밀리 응답 본문 없음");
        }
        if (response.respCd() == null || response.respCd() != RESP_CD_OK) {
            throw new MillieClientException("밀리 오류 응답 코드: " + response.respCd());
        }
        if (response.respData() == null
                || response.respData().content() == null
                || response.respData().content().list() == null) {
            throw new MillieClientException("밀리 응답 구조 누락");
        }
        return response.respData().content().list();
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
