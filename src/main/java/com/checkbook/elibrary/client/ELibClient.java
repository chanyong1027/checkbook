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

    /** 남은 시간(ms) — Jsoup 요청 시간 제한으로 쓴다. 다 썼으면 TIMEOUT */
    static int remainingMillis(long deadlineNanos) {
        return remainingMillis(deadlineNanos, System.nanoTime());
    }

    /**
     * Jsoup은 0을 '무제한'으로 읽고, 응답 대기를 시간 제한의 절반(정수 나눗셈)으로 둔다 — 1ms를 넘기면 응답 대기가 0, 곧 무제한이 된다.
     * 그래서 2ms 미만이면 보내지 않고 TIMEOUT
     */
    static int remainingMillis(long deadlineNanos, long nowNanos) {
        long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nowNanos);
        if (remaining < 2) {
            throw new ELibraryClientException(ELibraryClientException.Kind.TIMEOUT, "전자도서관 사이트 마감 초과");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
}
