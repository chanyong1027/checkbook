package com.checkbook.client.datanaru;

import com.checkbook.client.datanaru.dto.DatanaruBookExistResponse;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResponse;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
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

    private final RestClient restClient;
    private final String authKey;

    public DatanaruClient(
            @Value("${datanaru.base-url}") String baseUrl,
            @Value("${datanaru.auth-key}") String authKey,
            @Value("${datanaru.timeout:2000}") int timeout
    ) {
        this.authKey = authKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public DatanaruBookExistResult bookExist(String isbn13, String libCode) {
        DatanaruBookExistResponse response = restClient.get()
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
            DatanaruLibSrchResponse response = restClient.get()
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
