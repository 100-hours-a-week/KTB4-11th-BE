package com.stock_spoon.river_be.user.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserExceptionHandler.class);
    @ExceptionHandler(ProfileUpdateException.class)
    ResponseEntity<Map<String, String>> handle(ProfileUpdateException error) {
        log.warn("event=api_rejected status={} code={}", error.status().value(), error.code());
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
    @ExceptionHandler(AiSnapshotUnavailableException.class)
    ResponseEntity<Map<String, String>> handle(AiSnapshotUnavailableException error) {
        log.error("event=api_rejected status=503 code=MARKET_DATA_UNAVAILABLE stage=user_exception_handler causeType={}", error.getClass().getSimpleName());
        return ResponseEntity.status(503)
                .body(Map.of("code", "MARKET_DATA_UNAVAILABLE", "message", error.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(UserNotFoundException error) {
        log.warn("event=api_rejected status=404 code=USER_NOT_FOUND stage=user_exception_handler");
        return ResponseEntity.status(404)
                .body(Map.of("code", "USER_NOT_FOUND", "message", error.getMessage()));
    }
}
