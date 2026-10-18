package com.checkbook.common.exception;

import com.checkbook.common.ratelimit.ClientDailyLimitExceededException;
import com.checkbook.common.ratelimit.DailyBudgetExceededException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Duration;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
        log.warn("BusinessException: {}", exception.getMessage());
        return ResponseEntity.status(exception.getErrorCode().getHttpStatus())
                .body(ErrorResponse.of(exception.getErrorCode()));
    }

    @ExceptionHandler(ClientDailyLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleClientDailyLimit(ClientDailyLimitExceededException exception) {
        // 인터셉터가 IP별 첫 거절만 로그로 남긴다
        return withRetryAfter(ErrorCode.DAILY_REQUEST_LIMIT_EXCEEDED, exception.retryAfter());
    }

    // 섹션이 없는 엔드포인트(후보 검색·도서관 더보기)에서 공급자 예산이 다 찼을 때
    @ExceptionHandler(DailyBudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleProviderDailyLimit(DailyBudgetExceededException exception) {
        log.warn("공급자 하루 예산 소진: {}", exception.provider());
        return withRetryAfter(ErrorCode.PROVIDER_DAILY_LIMIT, exception.retryAfter());
    }

    private static ResponseEntity<ErrorResponse> withRetryAfter(ErrorCode errorCode, Duration retryAfter) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfter.toSeconds())))
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException exception) {
        log.warn("Missing param: {}", exception.getParameterName());

        ErrorCode errorCode = switch (exception.getParameterName()) {
            case "q", "query" -> ErrorCode.INVALID_SEARCH_KEYWORD;
            case "libraryIds" -> ErrorCode.LIBRARY_IDS_REQUIRED;
            case "isbn13" -> ErrorCode.ISBN_REQUIRED;
            case "lat", "lon" -> ErrorCode.INVALID_LOCATION;
            default -> ErrorCode.INVALID_SEARCH_KEYWORD;
        };

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        log.warn("Constraint violation: {}", exception.getMessage());

        ErrorCode errorCode = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> {
                    String propertyPath = violation.getPropertyPath().toString();
                    String annotationName = violation.getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName();

                    if (propertyPath.contains("libraryIds")) {
                        return ErrorCode.LIBRARY_IDS_REQUIRED;
                    }

                    if ("Size".equals(annotationName)) {
                        return ErrorCode.KEYWORD_TOO_LONG;
                    }

                    return ErrorCode.INVALID_SEARCH_KEYWORD;
                })
                .orElse(ErrorCode.INVALID_SEARCH_KEYWORD);

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Type mismatch param: {}", exception.getName());

        ErrorCode errorCode = switch (exception.getName()) {
            case "lat", "lon" -> ErrorCode.INVALID_LOCATION;
            default -> ErrorCode.INVALID_SEARCH_KEYWORD;
        };

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception exception) {
        log.error("Unexpected error", exception);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
