package com.stock_spoon.river_be.competition.controller;

import com.stock_spoon.river_be.competition.dto.CompetitionTradeHistoryResponse;
import com.stock_spoon.river_be.competition.service.CompetitionTradeHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/accounts/{accountId}/executions")
public class CompetitionTradeHistoryController {
    private final CompetitionTradeHistoryService history;

    @GetMapping
    public CompetitionTradeHistoryResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return history.list(Long.parseLong(jwt.getSubject()), accountId, cursor, limit);
    }
}
