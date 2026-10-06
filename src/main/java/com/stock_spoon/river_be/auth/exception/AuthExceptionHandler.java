package com.stock_spoon.river_be.auth.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthExceptionHandler.class);
    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> handle(AuthException error) {
        var level = error.status().is5xxServerError() ? log.atError() : log.atWarn();
        level.log("event=api_rejected status={} code={}", error.status().value(), error.code());
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception error) {
        log.warn("event=api_rejected status=400 code=INVALID_REQUEST");
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", "요청 형식이 올바르지 않습니다."));
    }
}
