package com.stock_spoon.river_be.user.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserExceptionHandler.class);
    @ExceptionHandler(AiSnapshotUnavailableException.class)
    ResponseEntity<Map<String, String>> handle(AiSnapshotUnavailableException error) {
        log.error("event=api_rejected status=503 code=MARKET_DATA_UNAVAILABLE");
        return ResponseEntity.status(503)
                .body(Map.of("code", "MARKET_DATA_UNAVAILABLE", "message", error.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(UserNotFoundException error) {
        log.warn("event=api_rejected status=404 code=USER_NOT_FOUND");
        return ResponseEntity.status(404)
                .body(Map.of("code", "USER_NOT_FOUND", "message", error.getMessage()));
    }
}
