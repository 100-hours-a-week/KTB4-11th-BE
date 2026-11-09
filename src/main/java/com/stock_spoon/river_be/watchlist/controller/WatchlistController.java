package com.stock_spoon.river_be.watchlist.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.config.DbWriteReplayBusinessCommand;
import static com.stock_spoon.river_be.config.DbWriteReplayBusinessCommand.Type.*;
import com.stock_spoon.river_be.watchlist.dto.WatchlistAddRequest;
import com.stock_spoon.river_be.watchlist.dto.WatchlistAddResponse;
import com.stock_spoon.river_be.watchlist.dto.WatchlistResponse;
import com.stock_spoon.river_be.watchlist.service.WatchlistService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관심 종목")
@RestController
@RequestMapping("/api/v2/users/me/watchlist")
public class WatchlistController {
    private final WatchlistService service;

    public WatchlistController(WatchlistService service) {
        this.service = service;
    }

    @DbReadRetryable
    @GetMapping
    @Operation(summary = "관심 종목 목록 조회")
    public WatchlistResponse list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping
    @DbWriteReplayBusinessCommand(WATCHLIST_ADD)
    @Operation(summary = "관심 종목 추가")
    public ResponseEntity<WatchlistAddResponse> add(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody WatchlistAddRequest request) {
        boolean created = service.add(Long.parseLong(jwt.getSubject()), request.stockCode());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new WatchlistAddResponse("success", request.stockCode()));
    }

    @DeleteMapping("/{stockCode}")
    @DbWriteReplayBusinessCommand(WATCHLIST_REMOVE)
    @Operation(summary = "관심 종목 삭제")
    public Map<String, String> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String stockCode) {
        service.remove(Long.parseLong(jwt.getSubject()), stockCode);
        return Map.of("message", "delete_success");
    }
}
