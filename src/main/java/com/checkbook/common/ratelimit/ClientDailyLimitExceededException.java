package com.checkbook.common.ratelimit;

import java.time.Duration;

/** 사용자(IP) 하루 요청 한도를 넘었다. 429 + Retry-After로 응답한다. */
public class ClientDailyLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public ClientDailyLimitExceededException(Duration retryAfter) {
        // 한도를 넘긴 쪽이 계속 두드려도 거절이 싸게 끝나도록 스택 추적을 만들지 않는다(리뷰 F11)
        super("사용자 하루 요청 한도 초과", null, false, false);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
