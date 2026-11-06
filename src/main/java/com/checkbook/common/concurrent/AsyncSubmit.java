package com.checkbook.common.concurrent;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 풀 거절(AbortPolicy) 시 supplyAsync가 동기로 던지는 RejectedExecutionException을
 * failedFuture로 변환 → 호출부가 실패한 작업으로 처리한다(SearchService는 데드라인 뒤 상태를 읽어 섹션 FAILED,
 * 도서관 fan-out은 해당 도서관 스킵).
 * 이 게이트가 없으면 부모 제출 거절은 500, 자식 제출 거절은 fan-out 전체 사망이 된다.
 *
 * <p>MDC(traceId)는 스레드마다 따로 있어 풀로 저절로 넘어가지 않으므로 제출 시점의 값을 복사해 넘긴다.
 * 작업(Supplier)이 아니라 실행기로 넘기는 Runnable을 감싼다 — 작업만 감싸면 풀 스레드에서 도는
 * exceptionally 같은 후속 단계의 로그에서 ID가 사라진다. 끝나면 지우지 않고 이전 값으로 되돌린다 —
 * callerRuns면 호출자 스레드에서 돌아서, 지우면 호출자의 ID까지 사라진다.
 */
public final class AsyncSubmit {

    private AsyncSubmit() {
    }

    public static <T> CompletableFuture<T> submitSafely(Supplier<T> task, ExecutorService executor) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        try {
            return CompletableFuture.supplyAsync(task, runnable -> executor.execute(withMdc(submitted, runnable)));
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Runnable withMdc(Map<String, String> context, Runnable runnable) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            replaceMdc(context);
            try {
                runnable.run();
            } finally {
                replaceMdc(previous);
            }
        };
    }

    // 값이 없으면 getCopyOfContextMap이 null을 줄 수 있고, setContextMap(null)은 구현에 따라 예외가 난다
    private static void replaceMdc(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
