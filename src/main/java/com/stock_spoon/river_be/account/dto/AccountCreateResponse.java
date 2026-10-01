package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stock_spoon.river_be.account.entity.Account;

public record AccountCreateResponse(
        @JsonProperty("account_id") Long accountId,
        @JsonProperty("account_name") String accountName,
        @JsonProperty("initial_capital") long initialCapital,
        @JsonProperty("cash_balance") long cashBalance,
        @JsonProperty("is_ai_managed") boolean aiManaged) {

    public static AccountCreateResponse from(Account account) {
        return new AccountCreateResponse(account.getId(), account.getName(),
                account.getInitialCapital(), account.getCashBalance(), account.isAiManaged());
    }
}
