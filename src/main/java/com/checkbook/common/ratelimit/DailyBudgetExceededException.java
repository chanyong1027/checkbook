package com.checkbook.common.ratelimit;

import java.time.Duration;

/** 공급자 하루 예산이 차단선에 닿아 호출하지 않았다. 요청은 나가지 않았다. */
public class DailyBudgetExceededException extends RuntimeException {

    private final ExternalProvider provider;
    private final Duration retryAfter;

    public DailyBudgetExceededException(ExternalProvider provider, Duration retryAfter) {
        // 흐름 제어용 예외라 스택 추적이 필요 없다
        super(provider.tag() + " 하루 호출 예산 소진으로 호출하지 않음", null, false, false);
        this.provider = provider;
        this.retryAfter = retryAfter;
    }

    public ExternalProvider provider() {
        return provider;
    }

    /** 예산이 다시 차는 KST 자정까지 남은 시간 */
    public Duration retryAfter() {
        return retryAfter;
    }
}
