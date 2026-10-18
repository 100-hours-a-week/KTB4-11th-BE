package com.stock_spoon.river_be.watchlist.controller;

import com.stock_spoon.river_be.watchlist.service.WatchlistException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WatchlistController.class)
public class WatchlistExceptionHandler {
    @ExceptionHandler(WatchlistException.class)
    ResponseEntity<Map<String, String>> handle(WatchlistException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}