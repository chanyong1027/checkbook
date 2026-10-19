package com.checkbook.featured.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLoanBookResult;
import com.checkbook.client.yes24.Yes24CategoryList;
import com.checkbook.client.yes24.Yes24Client;
import com.checkbook.client.yes24.Yes24Exception;
import com.checkbook.client.yes24.dto.Yes24CategoryListResponse.Item;
import com.checkbook.common.util.InputNormalizer;
import com.checkbook.featured.snapshot.domain.FeaturedBook;
import com.checkbook.featured.snapshot.domain.FeaturedSectionType;
import com.checkbook.featured.snapshot.domain.FeaturedSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Component
public class FeaturedBooksRefresher {

    // YES24 추천 목록은 categoryId가 필수다. 001 = 국내도서 전체
    private static final String YES24_DOMESTIC_BOOKS = "001";

    private final Yes24Client yes24Client;
    private final DatanaruClient datanaruClient;
    private final FeaturedBooksWriter writer;
    private final MeterRegistry meterRegistry;
    private final int pageSize;
    private final int yes24FetchSize;
    private final Duration bestsellerTtl;
    private final Duration newTtl;
    private final Duration loanTtl;

    public FeaturedBooksRefresher(
            Yes24Client yes24Client,
            DatanaruClient datanaruClient,
            FeaturedBooksWriter writer,
            MeterRegistry meterRegistry,
            @Value("${featured.page-size:15}") int pageSize,
            // 굿즈·상품 코드·성인 상품을 걸러도 page-size를 채우도록 더 받아 둔다(호출 수는 같다)
            @Value("${featured.yes24-fetch-size:30}") int yes24FetchSize,
            @Value("${featured.bestseller.ttl-hours:168}") long bestsellerTtlHours,
            @Value("${featured.new.ttl-hours:84}") long newTtlHours,
            @Value("${featured.loan.ttl-hours:168}") long loanTtlHours
    ) {
        this.yes24Client = yes24Client;
        this.datanaruClient = datanaruClient;
        this.writer = writer;
        this.meterRegistry = meterRegistry;
        this.pageSize = pageSize;
        this.yes24FetchSize = yes24FetchSize;
        this.bestsellerTtl = Duration.ofHours(bestsellerTtlHours);
        this.newTtl = Duration.ofHours(newTtlHours);
        this.loanTtl = Duration.ofHours(loanTtlHours);
        // 기동 때 0으로 미리 만든다. 실패 순간에 처음 생기면 첫 값이 1이라 increase()가 그 실패를 못 센다 —
        // 주간 배치는 다음 실패까지 일주일을 기다리고, 배포로 재시작할 때마다 되풀이된다
        for (FeaturedSectionType type : FeaturedSectionType.values()) {
            refreshCounter(type, "success");
            refreshCounter(type, "failure");
        }
    }

    /**
     * 트랜잭션 없음. 외부 API 호출 → 성공 시 writer.replaceSection, 실패 시 writer.markFailed.
     * 동시 갱신(워밍업 @Async ↔ cron) 직렬화는 Writer의 스냅샷 행 락(FOR UPDATE)이 담당 —
     * 여기서 synchronized로 잡으면 외부 호출(최대 수 분)까지 임계 구역에 들어가므로 두지 않는다.
     */
    public void refreshSection(FeaturedSectionType type) {
        try {
            switch (type) {
                case BESTSELLER -> refreshFromYes24(type, Yes24CategoryList.BESTSELLER, bestsellerTtl);
                case NEW        -> refreshFromYes24(type, Yes24CategoryList.NEW_ATTENTION, newTtl);
                case LOAN       -> refreshFromDatanaru(type, loanTtl);
            }
            log.info("featured 섹션 갱신 성공: type={}", type);
            refreshCounter(type, "success").increment();
        } catch (Exception e) {
            // 실패는 WARN 로그로만 남아 알림(ERROR 기준)에 안 잡힌다 — 이 카운터로 센다. 재시도 안내 WARN은 세지 않는다
            refreshCounter(type, "failure").increment();
            String reason = e.getClass().getSimpleName() + ": "
                    + truncate(e.getMessage(), 480);
            log.warn("featured 섹션 갱신 실패: type={}, reason={}", type, reason, e);
            try {
                writer.markFailed(type, reason);
            } catch (Exception inner) {
                log.error("featured markFailed 도중 추가 실패: type={}", type, inner);
            }
        }
    }

    private Counter refreshCounter(FeaturedSectionType type, String result) {
        return Counter.builder("featured.refresh")
                .description("featured 섹션 갱신 결과")
                .tag("type", type.name())
                .tag("result", result)
                .register(meterRegistry);
    }

    // 우리 쪽 선제한은 클라이언트가 최대 1초 기다려 준다(PR-3 결정 카드 3).
    // 재시작 직후 JVM의 첫 TLS 연결은 연결 제한(200ms)을 넘을 수 있는데, 첫 시도로 JVM이 데워져 두 번째는 빠르다.
    // 그래서 시간 초과만 1번 바로 다시 부른다. 한도·제한·429·형식 오류·5xx는 다시 해도 같아 재시도하지 않는다(카드 7)
    private void refreshFromYes24(FeaturedSectionType type, Yes24CategoryList list, Duration ttl) {
        List<Item> raw;
        try {
            raw = yes24Client.categoryList(list, YES24_DOMESTIC_BOOKS, yes24FetchSize);
        } catch (Yes24Exception e) {
            if (e.kind() != Yes24Exception.Kind.TIMEOUT) {
                throw e;
            }
            log.warn("YES24 목록 시간 초과, 1회 재시도: list={}", list);
            raw = yes24Client.categoryList(list, YES24_DOMESTIC_BOOKS, yes24FetchSize);
        }
        // 필수 필드 가드(DB NOT NULL 보호) + 눌렀을 때 상세 화면이 쓸모 있는 항목만:
        // ISBN이 아닌 상품 코드(달력 880… 등)는 '비표준 코드' 카드만 보이고, 메인 화면에는 나이 확인이 없다
        List<Item> valid = raw.stream()
                .filter(it -> InputNormalizer.isValidIsbn13(trimmed(it.isbn13())))
                .filter(it -> isNonBlank(it.title()))
                .filter(it -> !"Y".equalsIgnoreCase(it.adultYn()))
                .limit(pageSize)
                .toList();
        if (valid.isEmpty()) {
            // 외부 API가 빈 응답 또는 모두 invalid → 책 row 보존하기 위해 실패로 처리
            throw new IllegalStateException(
                    "YES24 목록 응답이 비었거나 필수 필드 누락: list=" + list
                            + ", raw=" + raw.size());
        }
        List<FeaturedBook> books = IntStream.range(0, valid.size())
                .mapToObj(i -> toBookFromYes24(type, i + 1, valid.get(i)))
                .toList();
        writer.replaceSection(type, FeaturedSource.YES24, books, ttl);
    }

    private void refreshFromDatanaru(FeaturedSectionType type, Duration ttl) {
        // 정보나루 loanItemSrch는 콜드 캐시 시 응답이 40초 이상 걸릴 수 있고,
        // 1차 호출이 서버 캐시를 데우므로 1회 재시도만 해도 2차는 즉시 응답하는 경향.
        List<DatanaruLoanBookResult> rows;
        try {
            rows = datanaruClient.loanItemSrch(pageSize);
        } catch (RuntimeException first) {
            log.warn("정보나루 loanItemSrch 1차 실패, 재시도: {}", first.toString());
            rows = datanaruClient.loanItemSrch(pageSize);
        }
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

    private FeaturedBook toBookFromYes24(FeaturedSectionType type, int rank, Item item) {
        return FeaturedBook.builder()
                .sectionType(type)
                .rank(rank)
                .isbn13(trimmed(item.isbn13()))
                .title(item.title().strip())
                .author(item.author())
                .publisher(item.publisher())
                .coverUrl(item.cover())
                .publishedAt(publishedAt(item.publishDate()))
                .build();
    }

    // YES24는 yyyyMMdd. 알라딘 때와 같은 yyyy-MM-dd로 맞추고, 모르는 형식은 버린다(열 길이 20을 넘으면 교체 전체가 실패한다)
    private static String publishedAt(String yes24Date) {
        if (yes24Date == null) return null;
        String value = yes24Date.strip();
        if (value.matches("\\d{8}")) {
            return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
        }
        return value.matches("\\d{4}-\\d{2}-\\d{2}") ? value : null;
    }

    private static String trimmed(String s) {
        return s == null ? null : s.strip();
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
