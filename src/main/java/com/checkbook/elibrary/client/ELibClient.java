package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;

import java.time.Duration;
import java.util.List;

public interface ELibClient {

    VendorType getVendorType();

    /** 사이트 마감이 없는 호출(조사·확인 도구)이 쓰는 마감 — 예전 Jsoup 요청당 시간 제한과 같은 값 */
    Duration DEFAULT_SITE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 사이트 마감(System.nanoTime 기준)까지 검색한다. 갈래·리다이렉트마다 새 요청을 보내더라도 그때 남은 시간만 쓰므로
     * 사이트 하나가 마감을 넘기지 않는다(T4 결정 카드 11). 넘으면 {@link ELibraryClientException.Kind#TIMEOUT}
     */
    List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword, long deadlineNanos);

    default List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword) {
        return search(baseUrl, keyword, System.nanoTime() + DEFAULT_SITE_TIMEOUT.toNanos());
    }

    /** 남은 시간(ms) — Jsoup 요청 시간 제한으로 쓴다. 다 썼으면 TIMEOUT(0을 넘기면 Jsoup은 '무제한'으로 읽는다) */
    static int remainingMillis(long deadlineNanos) {
        long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remaining <= 0) {
            throw new ELibraryClientException(ELibraryClientException.Kind.TIMEOUT, "전자도서관 사이트 마감 초과");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
}
