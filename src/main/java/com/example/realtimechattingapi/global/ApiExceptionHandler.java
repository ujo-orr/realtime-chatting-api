package com.example.realtimechattingapi.global;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
@lombok.extern.slf4j.Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException ex, HttpServletRequest request) {
        return response(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ResponseEntity<ApiError> invalid(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인해 주세요.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONFLICT", "이미 존재하거나 제약 조건과 충돌하는 데이터입니다.", request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ApiError> missing(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "리소스를 찾을 수 없습니다.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure: {} {}", request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다.", request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, request.getRequestURI()));
    }
}
