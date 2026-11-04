package com.checkbook.search.dto;

/**
 * 구독 서비스에서 이 책 제목을 검색하는 링크 하나. 사용자 브라우저가 연다(서버는 그 사이트를 부르지 않는다).
 * 그 서비스에 책이 있다는 뜻이 아니다 — 있음/없음을 확인하지 않았으므로 화면도 '찾아보기'로만 보인다.
 */
public record SubscriptionSearchLink(String name, String url) {
}
