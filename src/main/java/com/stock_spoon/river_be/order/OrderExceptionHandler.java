package com.stock_spoon.river_be.order;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {
    @ExceptionHandler(OrderException.class)
    ResponseEntity<Map<String, String>> handle(OrderException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
