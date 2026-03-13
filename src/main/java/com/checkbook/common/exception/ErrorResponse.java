package com.checkbook.common.exception;

import java.time.LocalDateTime;

public record ErrorResponse(

        String code,
        String message,
        LocalDateTime timestamp
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                errorCode.name(),
                errorCode.getMessage(),
                LocalDateTime.now()
        );
    }
}
