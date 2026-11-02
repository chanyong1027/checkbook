package com.checkbook.publiclibrary.snapshot.domain;

/** 스냅샷 갱신 실패 원인. 배치 응답이 "확인 못 함"의 이유와 자동 탐색 중단 여부를 정하는 데 쓴다. */
public enum SnapshotFailureCause {
    /** 호출 실패(타임아웃·오류 응답·429 등). */
    UPSTREAM_ERROR,
    /** 서킷이 열려 부르지 않음. */
    CIRCUIT_OPEN,
    /** 하루 예산 차단선이라 부르지 않음. */
    DAILY_LIMIT,
    /** 응답은 왔지만 소장·대출 값이 비었거나 형식이 틀림. */
    INVALID_RESPONSE
}
