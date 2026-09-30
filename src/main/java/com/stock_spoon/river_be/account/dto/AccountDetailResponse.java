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

    public static AccountDetailResponse from(Account account, long availableCash) {
        // ponytail: 대결·평가액·수익률·체결 횟수는 임시값. 해당 기능 연동 시 실제 값으로 교체한다.
        return new AccountDetailResponse("success", account.getId(), account.getName(), false,
                account.getInitialCapital(), account.getCashBalance(), availableCash,
                0, account.getCashBalance(), 0.0, 0);
    }
}
