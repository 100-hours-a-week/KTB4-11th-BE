package com.stock_spoon.river_be.user.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UserExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<Map<String, String>> handle(UserNotFoundException error) {
        return ResponseEntity.status(404)
                .body(Map.of("code", "USER_NOT_FOUND", "message", error.getMessage()));
    }
}
