package com.checkbook.publiclibrary.snapshot.domain;

public enum SnapshotSourceStatus {
    SUCCESS,  // API 성공으로 갱신된 fresh 데이터
    FAILED    // API 실패 — 만료된 snapshot은 대신 쓰지 않는다(D7-0 결정 카드 1)
}
