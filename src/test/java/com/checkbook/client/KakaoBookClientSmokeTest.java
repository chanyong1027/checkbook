package com.checkbook.client;

import com.checkbook.client.kakao.KakaoBookClient;
import com.checkbook.client.kakao.dto.KakaoBookResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KakaoBookClientSmokeTest {

    @Test
    void searchBooksWithRealApiReturnsDocumentsWhenKeyConfigured() {
        String restApiKey = readKakaoRestApiKey();
        assumeTrue(restApiKey != null && !restApiKey.isBlank(),
                "KAKAO_BOOK_REST_API_KEY is not configured");

        KakaoBookClient client = new KakaoBookClient(
                "https://dapi.kakao.com",
                restApiKey,
                5000
        );

        KakaoBookResponse response = client.searchBooks("자바", 1, 5);

        assertThat(response.meta()).isNotNull();
        assertThat(response.documents()).isNotNull().isNotEmpty();

        KakaoBookResponse.Document first = response.documents().get(0);
        assertThat(first.title()).isNotBlank();
        assertThat(first.isbn()).isNotBlank();
    }

    private String readKakaoRestApiKey() {
        String environmentValue = System.getenv("KAKAO_BOOK_REST_API_KEY");
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }

        return readFromDotEnv()
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private Optional<String> readFromDotEnv() {
        Path dotEnvPath = Path.of(".env");
        if (!Files.exists(dotEnvPath)) {
            return Optional.empty();
        }

        try {
            return Files.readAllLines(dotEnvPath).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .filter(line -> line.startsWith("KAKAO_BOOK_REST_API_KEY="))
                    .map(line -> line.substring("KAKAO_BOOK_REST_API_KEY=".length()).trim())
                    .map(this::stripQuotes)
                    .findFirst();
        } catch (IOException exception) {
            throw new IllegalStateException(".env 파일을 읽을 수 없습니다.", exception);
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }

        return value;
    }
}
