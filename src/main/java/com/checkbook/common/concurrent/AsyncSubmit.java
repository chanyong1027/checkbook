package com.checkbook.common.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 풀 거절(AbortPolicy) 시 supplyAsync가 동기로 던지는 RejectedExecutionException을
 * failedFuture로 변환 → 호출부의 exceptionally 폴백(섹션 FAILED/도서관 스킵)으로 흡수한다.
 * 이 게이트가 없으면 부모 제출 거절은 500, 자식 제출 거절은 fan-out 전체 사망이 된다.
 */
public final class AsyncSubmit {

    private AsyncSubmit() {
    }

    public static <T> CompletableFuture<T> submitSafely(Supplier<T> task, ExecutorService executor) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
