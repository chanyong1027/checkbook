package com.checkbook.client.datanaru;

import com.checkbook.client.datanaru.dto.DatanaruBookExistResponse;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResponse;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.client.datanaru.dto.DatanaruLoanItemResponse;
import com.checkbook.common.ratelimit.ExternalCallBudget;
import com.checkbook.common.ratelimit.ExternalProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class DatanaruClient {

    private final RestClient defaultClient;
    private final RestClient listClient;
    private final String authKey;
    private final Counter rateLimitedCounter;
    private final ExternalCallBudget budget;

    // builder는 Boot 자동 설정 빌더(prototype)라야 http.client.requests가 남는다 — RestClient.builder()로 만들면 관측이 안 붙는다
    public DatanaruClient(RestClient.Builder builder, DatanaruProperties properties, MeterRegistry meterRegistry,
                          ExternalCallBudget budget) {
        this.budget = budget;
        this.authKey = properties.authKey();
        this.defaultClient = buildClient(builder.clone(), properties.baseUrl(), properties.timeout());
        this.listClient = buildClient(builder.clone(), properties.baseUrl(), properties.listTimeout());
        this.rateLimitedCounter = Counter.builder("datanaru.rate_limited")
                .description("정보나루 429 Too Many Requests 수신 횟수 (rate limiter 재도입 트리거)")
                .register(meterRegistry);
    }

    private static RestClient buildClient(RestClient.Builder builder, String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** 사용자 요청 경로. 하루 예산 차단선이면 보내지 않고 DailyBudgetExceededException(서킷은 실패로 세지 않음). */
    public DatanaruBookExistResult bookExist(String isbn13, String libCode) {
        budget.acquire(ExternalProvider.DATANARU);
        DatanaruBookExistResponse response;
        try {
            response = defaultClient.get()
                    .uri("/bookExist?authKey={key}&libCode={code}&isbn13={isbn}&format=json",
                            authKey, libCode, isbn13)
                    .retrieve()
                    .body(DatanaruBookExistResponse.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            rateLimitedCounter.increment();
            log.warn("정보나루 bookExist 429 Too Many Requests: isbn13={}, libCode={} "
                    + "— rate limiter 재도입 트리거 관측", isbn13, libCode);
            throw new DatanaruResponseException(
                    "정보나루 rate limited(429): libCode=" + libCode, e);
        }

        if (response == null || response.response() == null || response.response().result() == null) {
            throw new DatanaruResponseException(
                    "유효하지 않은 응답: isbn13=" + isbn13 + ", libCode=" + libCode);
        }

        DatanaruBookExistResponse.Result result = response.response().result();
        return new DatanaruBookExistResult(
                libCode,
                "Y".equalsIgnoreCase(result.hasBook()),
                "Y".equalsIgnoreCase(result.loanAvailable())
        );
    }

    // 도서관 목록·인기 대출 목록은 배치라 예산 차단선을 넘어도 보낸다(세기만)
    public List<DatanaruLibSrchResult> libSrch(int pageNo, int pageSize) {
        budget.record(ExternalProvider.DATANARU);
        try {
            DatanaruLibSrchResponse response = defaultClient.get()
                    .uri("/libSrch?authKey={key}&pageNo={page}&pageSize={size}&format=json",
                            authKey, pageNo, pageSize)
                    .retrieve()
                    .body(DatanaruLibSrchResponse.class);

            if (response == null || response.response() == null || response.response().libs() == null) {
                return List.of();
            }

            return response.response().libs().stream()
                    .map(DatanaruLibSrchResponse.LibEntry::lib)
                    .filter(Objects::nonNull)
                    .map(lib -> new DatanaruLibSrchResult(
                            lib.libCode(),
                            lib.libName(),
                            lib.address(),
                            parseDouble(lib.latitude()),
                            parseDouble(lib.longitude()),
                            lib.region(),
                            lib.homepage(),
                            lib.tel(),
                            lib.fax(),
                            lib.operatingTime(),
                            lib.closed()))
                    .filter(result -> result.lat() != null && result.lon() != null)
                    .toList();
        } catch (Exception e) {
            throw new DatanaruResponseException("정보나루 libSrch 실패: pageNo=" + pageNo, e);
        }
    }

    public List<DatanaruLoanBookResult> loanItemSrch(int pageSize) {
        budget.record(ExternalProvider.DATANARU);
        try {
            DatanaruLoanItemResponse response = listClient.get()
                    .uri("/loanItemSrch?authKey={key}&pageNo=1&pageSize={size}&format=json",
                            authKey, pageSize)
                    .retrieve()
                    .body(DatanaruLoanItemResponse.class);

            if (response == null || response.response() == null || response.response().docs() == null) {
                return List.of();
            }

            return response.response().docs().stream()
                    .map(DatanaruLoanItemResponse.DocEntry::doc)
                    .filter(Objects::nonNull)
                    .map(this::toLoanResult)
                    .filter(r -> r.isbn13() != null && !r.isbn13().isBlank())
                    .toList();
        } catch (Exception e) {
            throw new DatanaruResponseException("정보나루 loanItemSrch 실패: pageSize=" + pageSize, e);
        }
    }

    private DatanaruLoanBookResult toLoanResult(DatanaruLoanItemResponse.Doc doc) {
        int rank = 0;
        try {
            if (doc.ranking() != null) rank = Integer.parseInt(doc.ranking().trim());
        } catch (NumberFormatException ignored) {
            // 순위 파싱 실패는 0으로 두고 후속 service에서 입력 순서로 처리
        }
        return new DatanaruLoanBookResult(
                rank,
                doc.isbn13(),
                doc.bookname(),
                doc.authors(),
                doc.publisher(),
                doc.bookImageUrl(),
                doc.publicationYear()
        );
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
