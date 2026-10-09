package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stock_spoon.river_be.account.entity.Account;

public record AccountDetailResponse(
        String message,
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("account_name") String accountName,
        @JsonProperty("is_duel_account") boolean duelAccount,
        @JsonProperty("initial_capital") long initialCapital,
        @JsonProperty("cash_balance") long cashBalance,
        @JsonProperty("available_cash") long availableCash,
        @JsonProperty("holdings_market_value") long holdingsMarketValue,
        @JsonProperty("total_assets") long totalAssets,
        @JsonProperty("return_percent") double returnPercent,
        @JsonProperty("executed_trade_count") long executedTradeCount) {

    public static AccountDetailResponse from(Account account, long availableCash, long holdingsMarketValue, long executedTradeCount) {
        // ponytail: 대결 계좌 상태는 임시값. 대결 계좌 연동 시 교체한다.
        long totalAssets = Math.addExact(account.getCashBalance(), holdingsMarketValue);
        double returnPercent = java.math.BigDecimal.valueOf(totalAssets)
                .subtract(java.math.BigDecimal.valueOf(account.getInitialCapital()))
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(account.getInitialCapital()), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
        return new AccountDetailResponse("success", account.getId(), account.getName(), false,
                account.getInitialCapital(), account.getCashBalance(), availableCash,
                holdingsMarketValue, totalAssets, returnPercent, executedTradeCount);
    }
}
