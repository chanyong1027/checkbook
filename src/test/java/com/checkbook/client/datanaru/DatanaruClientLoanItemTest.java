package com.checkbook.client.datanaru;

import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatanaruClientLoanItemTest {

    private MockWebServer server;
    private DatanaruClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new DatanaruClient(server.url("/").toString(), "AUTHKEY", 2000);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void loanItemSrch_parsesDocsAndRanking() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "response": {
                            "docs": [
                              {
                                "doc": {
                                  "ranking": "1",
                                  "bookname": "불편한 편의점",
                                  "authors": "김호연 지음",
                                  "publisher": "나무옆의자",
                                  "publication_year": "2021",
                                  "isbn13": "9791161571188",
                                  "bookImageURL": "https://cover.example/x.jpg"
                                }
                              }
                            ]
                          }
                        }
                        """));

        List<DatanaruLoanBookResult> result = client.loanItemSrch(15);

        assertThat(result).hasSize(1);
        DatanaruLoanBookResult first = result.get(0);
        assertThat(first.ranking()).isEqualTo(1);
        assertThat(first.isbn13()).isEqualTo("9791161571188");
        assertThat(first.title()).isEqualTo("불편한 편의점");
        assertThat(first.coverUrl()).isEqualTo("https://cover.example/x.jpg");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("/loanItemSrch");
        assertThat(request.getPath()).contains("pageSize=15");
        assertThat(request.getPath()).contains("authKey=AUTHKEY");
    }

    @Test
    void loanItemSrch_skipsItemsWithoutIsbn() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "response": {
                            "docs": [
                              { "doc": { "ranking": "1", "bookname": "no-isbn", "isbn13": "" } },
                              { "doc": { "ranking": "2", "bookname": "ok", "isbn13": "9780000000002" } }
                            ]
                          }
                        }
                        """));

        List<DatanaruLoanBookResult> result = client.loanItemSrch(15);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isbn13()).isEqualTo("9780000000002");
    }

    @Test
    void loanItemSrch_serverError_throwsDatanaruException() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> client.loanItemSrch(15))
                .isInstanceOf(DatanaruResponseException.class);
    }
}
