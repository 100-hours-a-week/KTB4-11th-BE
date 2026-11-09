package com.stock_spoon.river_be.account.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.stock_spoon.river_be.config.DbReadRetryable;
import com.stock_spoon.river_be.account.dto.AccountHoldingDetailResponse;
import com.stock_spoon.river_be.account.service.AccountHoldingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "계좌")
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountHoldingController {
    private final AccountHoldingsService holdings;

    public AccountHoldingController(AccountHoldingsService holdings) {
        this.holdings = holdings;
    }

    @DbReadRetryable
    @GetMapping("/{accountId}/holdings/{stockCode}")
    @Operation(summary = "보유 종목 상세 조회")
    public AccountHoldingDetailResponse get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @PathVariable String stockCode) {
        return holdings.get(Long.parseLong(jwt.getSubject()), accountId, stockCode);
    }
}
