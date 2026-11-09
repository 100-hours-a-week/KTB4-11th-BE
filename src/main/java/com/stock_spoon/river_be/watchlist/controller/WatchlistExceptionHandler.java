package com.stock_spoon.river_be.watchlist.controller;

import com.stock_spoon.river_be.watchlist.service.WatchlistException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WatchlistController.class)
public class WatchlistExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WatchlistExceptionHandler.class);
    @ExceptionHandler(WatchlistException.class)
    ResponseEntity<Map<String, String>> handle(WatchlistException error) {
        var level = error.status().is5xxServerError() ? log.atError() : log.atWarn();
        level.log("event=api_rejected stage=watchlist_exception_handler status={} code={}", error.status().value(), error.code());
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}