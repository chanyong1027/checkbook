package com.checkbook.client.yes24;

/**
 * 메인 추천에 쓰는 YES24 카테고리 목록. noResultCode는 '결과 없음' 404의 오류 코드다(실패가 아니라 빈 목록).
 */
public enum Yes24CategoryList {
    BESTSELLER("bestseller", "BEST_001"),
    NEW_ATTENTION("newproductAttention", "ATTN_001");

    private final String path;
    private final String noResultCode;

    Yes24CategoryList(String path, String noResultCode) {
        this.path = path;
        this.noResultCode = noResultCode;
    }

    String path() {
        return path;
    }

    String noResultCode() {
        return noResultCode;
    }
}
