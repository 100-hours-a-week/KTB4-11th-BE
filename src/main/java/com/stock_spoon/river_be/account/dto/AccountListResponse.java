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

    public static AccountListResponse from(Account account) {
        // ponytail: 임시 표시값. 대결 계좌와 보유종목 평가 연동 시 실제 상태·총자산·수익률로 교체한다.
        return new AccountListResponse(account.getId(), account.getName(), false,
                account.getCashBalance(), account.getCashBalance(), 0.0);
    }
}
