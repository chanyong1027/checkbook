package com.checkbook.featured.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.client.yes24.Yes24CategoryList;
import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Exception;
import com.checkbook.client.yes24.dto.Yes24CategoryListResponse.Item;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeaturedBooksRefresherTest {

    // 체크섬이 맞는 합성 ISBN (InputNormalizer 규칙)
    private static final String ISBN_A = "9788936434120";
    private static final String ISBN_B = "9791161571188";

    @Mock Yes24Client yes24Client;
    @Mock DatanaruClient datanaruClient;
    @Mock FeaturedBooksWriter writer;

    private FeaturedBooksRefresher refresher() {
        return new FeaturedBooksRefresher(yes24Client, datanaruClient, writer,
                15, 30, 168, 84, 168);
    }

    private static Item item(String isbn13, String title, String adultYn, String publishDate) {
        return new Item(1, title, "홍길동 저", "테스트출판", isbn13,
                "https://image.yes24.com/goods/1/L", publishDate, adultYn, "https://www.yes24.com/product/goods/1");
    }

    private List<FeaturedBook> capturedBooks(FeaturedSectionType type, Duration ttl) {
        ArgumentCaptor<List<FeaturedBook>> booksCap = ArgumentCaptor.forClass(List.class);
        verify(writer).replaceSection(eq(type), eq(FeaturedSource.YES24), booksCap.capture(), eq(ttl));
        return booksCap.getValue();
    }

    @Test
    void refreshSection_bestseller_callsYes24BestsellerAndMapsFields() {
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30))
                .thenReturn(List.of(item(ISBN_A, "소년이 온다", "N", "20140519")));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        List<FeaturedBook> books = capturedBooks(FeaturedSectionType.BESTSELLER, Duration.ofHours(168));
        assertThat(books).hasSize(1);
        FeaturedBook book = books.get(0);
        assertThat(book.getSectionType()).isEqualTo(FeaturedSectionType.BESTSELLER);
        assertThat(book.getRank()).isEqualTo(1);
        assertThat(book.getIsbn13()).isEqualTo(ISBN_A);
        assertThat(book.getTitle()).isEqualTo("소년이 온다");
        assertThat(book.getAuthor()).isEqualTo("홍길동 저");
        assertThat(book.getPublisher()).isEqualTo("테스트출판");
        assertThat(book.getCoverUrl()).isEqualTo("https://image.yes24.com/goods/1/L");
        // 알라딘 때와 같은 yyyy-MM-dd로 맞춘다(상세 카드에 그대로 보임)
        assertThat(book.getPublishedAt()).isEqualTo("2014-05-19");
    }

    @Test
    void refreshSection_new_usesNewAttentionListAndNewTtl() {
        when(yes24Client.categoryList(Yes24CategoryList.NEW_ATTENTION, "001", 30))
                .thenReturn(List.of(item(ISBN_A, "신간A", "N", "20261016")));

        refresher().refreshSection(FeaturedSectionType.NEW);

        assertThat(capturedBooks(FeaturedSectionType.NEW, Duration.ofHours(84))).hasSize(1);
    }

    @Test
    void refreshSection_yes24EmptyResponse_callsMarkFailed_doesNotReplace() {
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).thenReturn(List.of());

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        verify(writer).markFailed(eq(FeaturedSectionType.BESTSELLER),
                org.mockito.ArgumentMatchers.contains("비었거나 필수 필드"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }

    // ISBN 없음·ISBN이 아닌 상품 코드(달력 880…)·체크섬 틀림·제목 없음·성인 상품은 빼고, 순위는 남은 순서로 1부터
    @Test
    void refreshSection_filtersInvalidAndAdultItems_keepsRestInOrder() {
        when(yes24Client.categoryList(Yes24CategoryList.NEW_ATTENTION, "001", 30)).thenReturn(List.of(
                item(null,             "굿즈 세트",   "N", "20261020"),
                item("8809529018884",  "탁상달력",    "N", "20261030"),
                item("9788936434121",  "체크섬 틀림", "N", "20261001"),
                item(ISBN_A,           "  ",          "N", "20261002"),
                item(ISBN_B,           "성인 상품",   "Y", "20261003"),
                item(" " + ISBN_A + " ", "정상 1",    "N", "20261004"),
                item(ISBN_B,           "정상 2",      null, "20261005")
        ));

        refresher().refreshSection(FeaturedSectionType.NEW);

        List<FeaturedBook> books = capturedBooks(FeaturedSectionType.NEW, Duration.ofHours(84));
        assertThat(books).extracting(FeaturedBook::getTitle).containsExactly("정상 1", "정상 2");
        assertThat(books).extracting(FeaturedBook::getRank).containsExactly(1, 2);
        assertThat(books.get(0).getIsbn13()).isEqualTo(ISBN_A);
    }

    // 30권을 받아 걸러도 화면에는 page-size(15)권까지만
    @Test
    void refreshSection_keepsAtMostPageSizeValidItems() {
        List<Item> thirty = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> item(ISBN_A, "책 " + i, "N", "20261001"))
                .toList();
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).thenReturn(thirty);

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        List<FeaturedBook> books = capturedBooks(FeaturedSectionType.BESTSELLER, Duration.ofHours(168));
        assertThat(books).hasSize(15);
        assertThat(books.get(14).getTitle()).isEqualTo("책 15");
        assertThat(books.get(14).getRank()).isEqualTo(15);
    }

    @Test
    void refreshSection_allItemsInvalid_callsMarkFailed() {
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).thenReturn(List.of(
                item("8809529018884", "탁상달력", "N", "20261030")
        ));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        verify(writer).markFailed(eq(FeaturedSectionType.BESTSELLER),
                org.mockito.ArgumentMatchers.contains("비었거나 필수 필드"));
        verify(writer, never()).replaceSection(any(), any(), anyList(), any());
    }

    // 출간일이 8자리 숫자가 아니면 보이지 않게(null) — 열 길이(20)를 넘는 값으로 교체 전체가 실패하지 않게
    @Test
    void refreshSection_unexpectedPublishDate_keepsBookWithoutDate() {
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30)).thenReturn(List.of(
                item(ISBN_A, "날짜 없음", "N", null),
                item(ISBN_B, "이상한 날짜", "N", "2026년 10월 16일 예정 (예약판매)"),
                item(ISBN_A, "이미 형식", "N", "2026-10-16")
        ));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        assertThat(capturedBooks(FeaturedSectionType.BESTSELLER, Duration.ofHours(168)))
                .extracting(FeaturedBook::getPublishedAt)
                .containsExactly(null, null, "2026-10-16");
    }

    // 선제한·일일 중단·타임아웃 등 YES24 실패는 재시도 없이 실패 기록, 기존 행은 그대로(교체 안 함)
    @Test
    void refreshSection_yes24Failure_marksFailedWithoutRetry() {
        when(yes24Client.categoryList(Yes24CategoryList.BESTSELLER, "001", 30))
                .thenThrow(new Yes24Exception(Yes24Exception.Kind.DAILY_LIMIT, false, "YES24 일일 한도 소진으로 호출 중단 중"));

        refresher().refreshSection(FeaturedSectionType.BESTSELLER);

        verify(yes24Client, times(1)).categoryList(Yes24CategoryList.BESTSELLER, "001", 30);
        verify(writer).markFailed(eq(FeaturedSectionType.BESTSELLER),
                org.mockito.ArgumentMatchers.contains("Yes24Exception: YES24 일일 한도"));
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

    @Test
    void refreshSection_loan_retriesOnceAfterFailure_andSucceeds() {
        when(datanaruClient.loanItemSrch(15))
                .thenThrow(new RuntimeException("read timed out"))
                .thenReturn(List.of(
                        new DatanaruLoanBookResult(1, "9791161571188", "불편한 편의점",
                                "김호연", "나무옆의자", "https://cover/x.jpg", "2021")
                ));

        refresher().refreshSection(FeaturedSectionType.LOAN);

        verify(datanaruClient, times(2)).loanItemSrch(15);
        verify(writer).replaceSection(
                eq(FeaturedSectionType.LOAN),
                eq(FeaturedSource.DATANARU),
                anyList(),
                eq(Duration.ofHours(168))
        );
        verify(writer, never()).markFailed(any(), org.mockito.ArgumentMatchers.anyString());
    }
}
