package com.checkbook.common.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException exception) {
        log.warn("BusinessException: {}", exception.getMessage());
        return ResponseEntity.status(exception.getErrorCode().getHttpStatus())
                .body(ErrorResponse.of(exception.getErrorCode()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException exception) {
        log.warn("Missing param: {}", exception.getParameterName());

        ErrorCode errorCode = switch (exception.getParameterName()) {
            case "q", "query" -> ErrorCode.INVALID_SEARCH_KEYWORD;
            case "libraryIds" -> ErrorCode.LIBRARY_IDS_REQUIRED;
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

                    if ("Size".equals(annotationName)) {
                        return ErrorCode.KEYWORD_TOO_LONG;
                    }

                    if (propertyPath.contains("libraryIds")) {
                        return ErrorCode.LIBRARY_IDS_REQUIRED;
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
