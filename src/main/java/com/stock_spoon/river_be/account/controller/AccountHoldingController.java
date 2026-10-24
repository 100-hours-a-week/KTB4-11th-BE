package com.stock_spoon.river_be.account.controller;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.account.dto.AccountHoldingDetailResponse;
import com.stock_spoon.river_be.account.service.AccountHoldingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountHoldingController {
    private final AccountHoldingsService holdings;

    public AccountHoldingController(AccountHoldingsService holdings) {
        this.holdings = holdings;
    }

    @DbReadRetryable
    @GetMapping("/{accountId}/holdings/{stockCode}")
    public AccountHoldingDetailResponse get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @PathVariable String stockCode) {
        return holdings.get(Long.parseLong(jwt.getSubject()), accountId, stockCode);
    }
}
