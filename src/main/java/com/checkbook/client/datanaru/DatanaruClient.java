package com.checkbook.client.datanaru;

import com.checkbook.client.datanaru.dto.DatanaruBookExistResponse;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResponse;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.client.datanaru.dto.DatanaruLoanItemResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class DatanaruClient {

    private final RestClient defaultClient;
    private final RestClient listClient;
    private final String authKey;

    public DatanaruClient(
            @Value("${datanaru.base-url}") String baseUrl,
            @Value("${datanaru.auth-key}") String authKey,
            @Value("${datanaru.timeout:2000}") int timeout,
            @Value("${datanaru.list-timeout:10000}") int listTimeout
    ) {
        this.authKey = authKey;
        this.defaultClient = buildClient(baseUrl, timeout);
        this.listClient = buildClient(baseUrl, listTimeout);
    }

    private static RestClient buildClient(String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public DatanaruBookExistResult bookExist(String isbn13, String libCode) {
        DatanaruBookExistResponse response = defaultClient.get()
                .uri("/bookExist?authKey={key}&libCode={code}&isbn13={isbn}&format=json",
                        authKey, libCode, isbn13)
                .retrieve()
                .body(DatanaruBookExistResponse.class);

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

    public List<DatanaruLibSrchResult> libSrch(int pageNo, int pageSize) {
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
