package com.checkbook.featured.service;

import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.AladinListQueryType;
import com.checkbook.client.aladin.dto.AladinItemResponse.Item;
import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Component
public class FeaturedBooksRefresher {

    private final AladinClient aladinClient;
    private final DatanaruClient datanaruClient;
    private final FeaturedBooksWriter writer;
    private final int pageSize;
    private final Duration bestsellerTtl;
    private final Duration newTtl;
    private final Duration loanTtl;

    public FeaturedBooksRefresher(
            AladinClient aladinClient,
            DatanaruClient datanaruClient,
            FeaturedBooksWriter writer,
            @Value("${featured.page-size:15}") int pageSize,
            @Value("${featured.bestseller.ttl-hours:168}") long bestsellerTtlHours,
            @Value("${featured.new.ttl-hours:84}") long newTtlHours,
            @Value("${featured.loan.ttl-hours:168}") long loanTtlHours
    ) {
        this.aladinClient = aladinClient;
        this.datanaruClient = datanaruClient;
        this.writer = writer;
        this.pageSize = pageSize;
        this.bestsellerTtl = Duration.ofHours(bestsellerTtlHours);
        this.newTtl = Duration.ofHours(newTtlHours);
        this.loanTtl = Duration.ofHours(loanTtlHours);
    }

    /** 트랜잭션 없음. 외부 API 호출 → 성공 시 writer.replaceSection, 실패 시 writer.markFailed. */
    public void refreshSection(FeaturedSectionType type) {
        try {
            switch (type) {
                case BESTSELLER -> refreshFromAladin(type, AladinListQueryType.BESTSELLER, bestsellerTtl);
                case NEW        -> refreshFromAladin(type, AladinListQueryType.ITEM_NEW_SPECIAL, newTtl);
                case LOAN       -> refreshFromDatanaru(type, loanTtl);
            }
            log.info("featured 섹션 갱신 성공: type={}", type);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": "
                    + truncate(e.getMessage(), 480);
            log.warn("featured 섹션 갱신 실패: type={}, reason={}", type, reason);
            try {
                writer.markFailed(type, reason);
            } catch (Exception inner) {
                log.error("featured markFailed 도중 추가 실패: type={}", type, inner);
            }
        }
    }

    private void refreshFromAladin(FeaturedSectionType type, AladinListQueryType qt, Duration ttl) {
        List<Item> raw = aladinClient.itemList(qt, pageSize);
        // 필수 필드 가드: isbn13/title 둘 다 non-blank인 항목만 통과 (DB NOT NULL 보호)
        List<Item> valid = raw.stream()
                .filter(it -> isNonBlank(it.isbn13()) && isNonBlank(it.title()))
                .toList();
        if (valid.isEmpty()) {
            // 외부 API가 빈 응답 또는 모두 invalid → 책 row 보존하기 위해 실패로 처리
            throw new IllegalStateException(
                    "알라딘 ItemList 응답이 비었거나 필수 필드 누락: queryType=" + qt
                            + ", raw=" + raw.size());
        }
        List<FeaturedBook> books = IntStream.range(0, valid.size())
                .mapToObj(i -> toBookFromAladin(type, i + 1, valid.get(i)))
                .toList();
        writer.replaceSection(type, FeaturedSource.ALADIN, books, ttl);
    }

    private void refreshFromDatanaru(FeaturedSectionType type, Duration ttl) {
        List<DatanaruLoanBookResult> rows = datanaruClient.loanItemSrch(pageSize);
        // DatanaruClient.loanItemSrch가 이미 isbn13 blank를 걸러주지만 title 가드 추가
        List<DatanaruLoanBookResult> valid = rows.stream()
                .filter(r -> isNonBlank(r.title()))
                .toList();
        if (valid.isEmpty()) {
            throw new IllegalStateException(
                    "정보나루 loanItemSrch 응답이 비었거나 필수 필드 누락: raw=" + rows.size());
        }
        List<FeaturedBook> books = IntStream.range(0, valid.size())
                .mapToObj(i -> toBookFromDatanaru(type, i + 1, valid.get(i)))
                .toList();
        writer.replaceSection(type, FeaturedSource.DATANARU, books, ttl);
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private FeaturedBook toBookFromAladin(FeaturedSectionType type, int rank, Item item) {
        return FeaturedBook.builder()
                .sectionType(type)
                .rank(rank)
                .isbn13(item.isbn13())
                .title(item.title())
                .author(item.author())
                .publisher(item.publisher())
                .coverUrl(item.cover())
                .publishedAt(item.pubDate())
                .build();
    }

    private FeaturedBook toBookFromDatanaru(FeaturedSectionType type, int rank,
                                             DatanaruLoanBookResult row) {
        return FeaturedBook.builder()
                .sectionType(type)
                .rank(rank)
                .isbn13(row.isbn13())
                .title(row.title())
                .author(row.author())
                .publisher(row.publisher())
                .coverUrl(row.coverUrl())
                .publishedAt(row.publishedAt())
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
