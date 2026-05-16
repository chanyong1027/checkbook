package com.checkbook.featured.service;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.AladinListQueryType;
import com.checkbook.client.aladin.dto.AladinItemResponse.Item;
import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeaturedBooksRefresherTest {

    @Mock AladinClient aladinClient;
    @Mock DatanaruClient datanaruClient;
    @Mock FeaturedBooksWriter writer;

    private FeaturedBooksRefresher refresher() {
        return new FeaturedBooksRefresher(aladinClient, datanaruClient, writer,
                15, 168, 84, 168);
    }

    @Test
    void refreshSection_bestseller_callsAladinAndWriter() {
        List<Item> items = List.of(
                new Item("9788936434120", "소년이 온다", "한강", "창비",
                        "https://cover/1.jpg", "2014-05-19", 12345L, 13500, null)
        );
        when(aladinClient.itemList(AladinListQueryType.BESTSELLER, 15)).thenReturn(items);

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        ArgumentCaptor<List<FeaturedBook>> booksCap = ArgumentCaptor.forClass(List.class);
        verify(writer).replaceSection(
                eq(FeaturedSectionType.BESTSELLER),
                eq(FeaturedSource.ALADIN),
                booksCap.capture(),
                eq(Duration.ofHours(168))
        );
        assertThat(booksCap.getValue()).hasSize(1);
        FeaturedBook book = booksCap.getValue().get(0);
        assertThat(book.getRank()).isEqualTo(1);
        assertThat(book.getIsbn13()).isEqualTo("9788936434120");
        assertThat(book.getPublishedAt()).isEqualTo("2014-05-19");
    }

    @Test
    void refreshSection_new_usesItemNewSpecialQueryTypeAndNewTtl() {
        // 빈 응답은 실패로 취급되므로 1건이라도 정상 응답을 주입해 QueryType/TTL을 검증
        when(aladinClient.itemList(AladinListQueryType.ITEM_NEW_SPECIAL, 15))
                .thenReturn(List.of(
                        new Item("9788900000002", "신간A", "저자", "출판사",
                                "https://cover/a.jpg", "2026-05-01", 1L, 10000, null)
                ));

        refresher().refreshSection(FeaturedSectionType.NEW);

        verify(writer).replaceSection(
                eq(FeaturedSectionType.NEW),
                eq(FeaturedSource.ALADIN),
                anyList(),
                eq(Duration.ofHours(84))
        );
    }

    @Test
    void refreshSection_aladinEmptyResponse_callsMarkFailed_doesNotReplace() {
        when(aladinClient.itemList(AladinListQueryType.BESTSELLER, 15))
                .thenReturn(List.of());

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        verify(writer).markFailed(eq(FeaturedSectionType.BESTSELLER),
                org.mockito.ArgumentMatchers.contains("비었거나 필수 필드"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }

    @Test
    void refreshSection_aladinFiltersInvalidItems_keepsRest() {
        // 1건은 isbn13 null (invalid), 1건은 title blank (invalid), 1건은 정상
        when(aladinClient.itemList(AladinListQueryType.BESTSELLER, 15)).thenReturn(List.of(
                new Item(null,            "이상치1", "저자", "출판사", "c", "2026-01-01", 1L, 100, null),
                new Item("9780000000010", "  ",     "저자", "출판사", "c", "2026-01-02", 2L, 100, null),
                new Item("9780000000020", "정상책",  "저자", "출판사", "c", "2026-01-03", 3L, 100, null)
        ));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        ArgumentCaptor<List<FeaturedBook>> booksCap = ArgumentCaptor.forClass(List.class);
        verify(writer).replaceSection(eq(FeaturedSectionType.BESTSELLER),
                eq(FeaturedSource.ALADIN), booksCap.capture(), eq(Duration.ofHours(168)));
        assertThat(booksCap.getValue()).hasSize(1);
        assertThat(booksCap.getValue().get(0).getIsbn13()).isEqualTo("9780000000020");
        assertThat(booksCap.getValue().get(0).getRank()).isEqualTo(1); // rank는 valid 순서로 1부터
    }

    @Test
    void refreshSection_aladinAllItemsInvalid_callsMarkFailed() {
        when(aladinClient.itemList(AladinListQueryType.BESTSELLER, 15)).thenReturn(List.of(
                new Item(null, "이상치", "저자", "출판사", "c", "2026-01-01", 1L, 100, null)
        ));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        verify(writer).markFailed(eq(FeaturedSectionType.BESTSELLER),
                org.mockito.ArgumentMatchers.contains("비었거나 필수 필드"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }

    @Test
    void refreshSection_datanaruEmptyResponse_callsMarkFailed() {
        when(datanaruClient.loanItemSrch(15)).thenReturn(List.of());

        refresher().refreshSection(FeaturedSectionType.LOAN);

        verify(writer).markFailed(eq(FeaturedSectionType.LOAN),
                org.mockito.ArgumentMatchers.contains("비었거나 필수 필드"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }

    @Test
    void refreshSection_loan_callsDatanaru() {
        when(datanaruClient.loanItemSrch(15)).thenReturn(List.of(
                new DatanaruLoanBookResult(1, "9791161571188", "불편한 편의점",
                        "김호연", "나무옆의자", "https://cover/x.jpg", "2021")
        ));

        refresher().refreshSection(FeaturedSectionType.LOAN);

        verify(writer).replaceSection(
                eq(FeaturedSectionType.LOAN),
                eq(FeaturedSource.DATANARU),
                anyList(),
                eq(Duration.ofHours(168))
        );
    }

    @Test
    void refreshSection_externalFailure_callsMarkFailed_doesNotCallReplace() {
        when(datanaruClient.loanItemSrch(15))
                .thenThrow(new RuntimeException("timeout after 2000ms"));

        refresher().refreshSection(FeaturedSectionType.LOAN);

        verify(writer).markFailed(eq(FeaturedSectionType.LOAN),
                org.mockito.ArgumentMatchers.contains("timeout after 2000ms"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }
}
