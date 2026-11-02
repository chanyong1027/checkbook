package com.checkbook.publiclibrary.dto;

/**
 * 화면이 자동 탐색(다음 배치)을 멈춰야 하는 이유. NONE이 아니면 남은 작업 위에 다음 배치를 쌓지 않는다.
 * nextOffset은 그대로 주므로 사용자가 더보기로 이어 찾을 수 있다.
 */
public enum BatchStopReason {
    NONE,
    /** 마감까지 끝나지 않은 기관이 있다 — 그 호출이 아직 스레드를 잡고 있을 수 있다. */
    BATCH_DEADLINE,
    /** 풀이 작업을 거절했다 — 서버가 바쁘다. */
    POOL_BUSY
}
