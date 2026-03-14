package com.checkbook.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_SEARCH_KEYWORD(HttpStatus.BAD_REQUEST, "검색어를 입력해주세요."),
    KEYWORD_TOO_LONG(HttpStatus.BAD_REQUEST, "검색어는 200자 이내로 입력해주세요."),
    INVALID_REGION_CODE(HttpStatus.BAD_REQUEST, "올바른 지역 코드를 입력해주세요."),
    INVALID_LOCATION(HttpStatus.BAD_REQUEST, "올바른 위치 정보를 입력해주세요."),

    LIBRARY_IDS_REQUIRED(HttpStatus.BAD_REQUEST, "도서관을 선택해주세요."),
    LIBRARY_IDS_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "도서관은 최대 20개까지 선택할 수 있습니다."),
    INVALID_LIBRARY_ID(HttpStatus.BAD_REQUEST, "올바른 도서관 ID를 입력해주세요."),

    INVALID_VENDOR_TYPE(HttpStatus.BAD_REQUEST, "올바른 벤더 타입을 입력해주세요. (KYOBO, BOOKCUBE)"),

    LIBRARY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 도서관입니다."),

    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
