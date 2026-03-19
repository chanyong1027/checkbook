package com.checkbook.publiclibrary.snapshot.domain;

public enum SnapshotSourceStatus {
    SUCCESS,  // API 성공으로 갱신된 fresh 데이터
    STALE,    // TTL 초과, API 장애로 인해 오래된 데이터 반환
    FAILED    // snapshot 없고 API도 실패
}
