package com.stock_spoon.river_be.order;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderExceptionHandler.class);
    @ExceptionHandler(OrderException.class)
    ResponseEntity<Map<String, String>> handle(OrderException error) {
        var level = error.status().is5xxServerError() ? log.atError() : log.atWarn();
        level.log("event=api_rejected status={} code={}", error.status().value(), error.code());
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
