package com.checkbook.search.dto;

/**
 * 프런트가 이미 가진 후보 정보(카카오). /api/search의 선택 파라미터로 받는다.
 * YES24가 실패하거나 못 찾았을 때 화면 표시와 밀리 매칭에만 쓴다. 가격·링크에는 쓰지 않고 저장하지 않는다.
 */
public record BookHint(
        String title,
        String author,
        String publisher,
        String coverUrl
) {
}
