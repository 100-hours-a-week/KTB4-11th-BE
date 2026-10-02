package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stock_spoon.river_be.account.entity.Account;

public record AccountListResponse(
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("account_name") String accountName,
        @JsonProperty("is_duel_account") boolean duelAccount,
        @JsonProperty("cash_balance") long cashBalance,
        @JsonProperty("total_assets") long totalAssets,
        @JsonProperty("return_percent") double returnPercent) {

    public static AccountListResponse from(Account account, long holdingsMarketValue) {
        // ponytail: 대결 계좌 상태는 임시값. 대결 계좌 연동 시 교체한다.
        long totalAssets = Math.addExact(account.getCashBalance(), holdingsMarketValue);
        double returnPercent = java.math.BigDecimal.valueOf(totalAssets)
                .subtract(java.math.BigDecimal.valueOf(account.getInitialCapital()))
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(account.getInitialCapital()), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
        return new AccountListResponse(account.getId(), account.getName(), false,
                account.getCashBalance(), totalAssets, returnPercent);
    }
}
