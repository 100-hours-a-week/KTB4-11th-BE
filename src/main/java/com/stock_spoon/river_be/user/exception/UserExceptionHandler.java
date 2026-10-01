package com.stock_spoon.river_be.user.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler {
    @ExceptionHandler(AiSnapshotUnavailableException.class)
    ResponseEntity<Map<String, String>> handle(AiSnapshotUnavailableException error) {
        return ResponseEntity.status(503)
                .body(Map.of("code", "MARKET_DATA_UNAVAILABLE", "message", error.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(UserNotFoundException error) {
        return ResponseEntity.status(404)
                .body(Map.of("code", "USER_NOT_FOUND", "message", error.getMessage()));
    }
}
