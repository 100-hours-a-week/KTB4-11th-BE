package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stock_spoon.river_be.account.entity.Account;

public record AccountResponse(
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("account_name") String accountName,
        @JsonProperty("initial_capital") long initialCapital,
        @JsonProperty("cash_balance") long cashBalance,
        @JsonProperty("ai_delegated") boolean aiDelegated) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(),
                account.getInitialCapital(), account.getCashBalance(), account.isAiDelegated());
    }
}
