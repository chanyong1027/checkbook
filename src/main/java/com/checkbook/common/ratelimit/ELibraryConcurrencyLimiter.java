package com.checkbook.common.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 전자도서관 사이트 하나에 동시에 보내는 요청 수 상한(결정 notes/decisions/2026-10-20-전자도서관-전수조사-결정카드.md).
 * 하루 상한({@link ExternalCallBudget#acquireELibrary})이 양을 막는다면, 이것은 한 순간에 몰리는 것을 막는다.
 * 사이트는 도서관 행(id)으로 센다 — 하루 상한과 같은 열쇠다.
 *
 * 꺼져 있으면(부하 측정용) 막지 않는다. 부하 테스트는 모든 사이트가 WireMock 한 호스트라 걸리면 측정이 왜곡된다.
 */
public class ELibraryConcurrencyLimiter {

    private final boolean enabled;
    private final int permitsPerLibrary;
    // 사이트 수(수천 미만)만큼만 생기고 지우지 않는다. 지우면 자리를 쥔 쪽과 새로 만든 쪽이 다른 세마포어를 보게 된다
    private final ConcurrentHashMap<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    public ELibraryConcurrencyLimiter(RateLimitProperties properties) {
        this.enabled = properties.enabled();
        this.permitsPerLibrary = properties.elibraryPerLibraryConcurrency();
    }

    /**
     * 자리를 얻으면 true. 자리가 없으면 timeoutMs까지 기다리고, 그래도 없거나 인터럽트되면 false.
     * true를 받은 쪽만 {@link #release}를 부른다.
     */
    public boolean tryAcquire(long libraryId, long timeoutMs) {
        if (!enabled) {
            return true;
        }
        try {
            return semaphore(libraryId).tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release(long libraryId) {
        if (enabled) {
            semaphore(libraryId).release();
        }
    }

    // 공정(fair) 모드: 먼저 기다린 요청이 먼저 얻는다. 늦게 온 요청이 끼어들어 앞 요청이 마감을 넘기지 않게
    private Semaphore semaphore(long libraryId) {
        return semaphores.computeIfAbsent(libraryId, id -> new Semaphore(permitsPerLibrary, true));
    }
}
