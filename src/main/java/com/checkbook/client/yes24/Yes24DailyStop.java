package com.checkbook.client.yes24;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 일일 한도(RATE_002) 소진 후 해제 시각까지 YES24 호출을 멈추는 인메모리 상태.
 * 단일 서버 초기안이다. 재시작하면 사라지고, 이미 나간 요청은 추가 429를 받을 수 있다.
 */
class Yes24DailyStop {

    private final AtomicReference<Instant> until = new AtomicReference<>(Instant.MIN);

    boolean isActive(Instant now) {
        return now.isBefore(until.get());
    }

    // 이미 나간 요청들의 RATE_002가 순서 없이 도착해도 가장 늦은 해제 시각을 유지한다
    void stopUntil(Instant newUntil) {
        until.accumulateAndGet(newUntil, (current, candidate) -> candidate.isAfter(current) ? candidate : current);
    }

    Instant until() {
        return until.get();
    }
}
