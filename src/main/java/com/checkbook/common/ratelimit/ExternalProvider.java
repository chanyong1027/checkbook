package com.checkbook.common.ratelimit;

/** 하루 호출 예산을 세는 외부 공급자. 전자도서관은 도서관 사이트마다 따로 센다. */
public enum ExternalProvider {
    YES24,
    DATANARU,
    MILLIE,
    KAKAO,
    ELIBRARY;

    String tag() {
        return name().toLowerCase();
    }
}
