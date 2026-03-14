package com.checkbook.client;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruBookExistResult;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatanaruClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void bookExistParsesFlags() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "response": {
                            "result": {
                              "hasBook": "Y",
                              "loanAvailable": "N"
                            }
                          }
                        }
                        """));
        server.start();

        DatanaruClient client = new DatanaruClient(baseUrl("/api"), "test-key", 200);

        DatanaruBookExistResult result = client.bookExist("9788936439743", "111111");

        assertThat(result.libCode()).isEqualTo("111111");
        assertThat(result.hasBook()).isTrue();
        assertThat(result.loanAvailable()).isFalse();
    }

    @Test
    void libSrchParsesLibrariesAndFiltersInvalidCoordinates() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "response": {
                            "libs": [
                              {
                                "lib": {
                                  "libCode": "111111",
                                  "libName": "종로도서관",
                                  "address": "서울 종로구",
                                  "latitude": "37.57",
                                  "longitude": "126.98",
                                  "region": "11",
                                  "homepage": "https://lib.example"
                                }
                              },
                              {
                                "lib": {
                                  "libCode": "222222",
                                  "libName": "좌표오류도서관",
                                  "address": "서울 어딘가",
                                  "latitude": "",
                                  "longitude": "126.98",
                                  "region": "11",
                                  "homepage": "https://invalid.example"
                                }
                              }
                            ]
                          }
                        }
                        """));
        server.start();

        DatanaruClient client = new DatanaruClient(baseUrl("/api"), "test-key", 200);

        List<DatanaruLibSrchResult> result = client.libSrch(1, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).libCode()).isEqualTo("111111");
        assertThat(result.get(0).lat()).isEqualTo(37.57);
    }

    @Test
    void apiFailureReturnsSafeDefaults() {
        DatanaruClient client = new DatanaruClient("http://127.0.0.1:1/api", "test-key", 50);

        assertThat(client.bookExist("9788936439743", "111111"))
                .isEqualTo(new DatanaruBookExistResult("111111", false, false));
        assertThat(client.libSrch(1, 100)).isEmpty();
    }

    private String baseUrl(String path) {
        return server.url(path).toString();
    }
}
