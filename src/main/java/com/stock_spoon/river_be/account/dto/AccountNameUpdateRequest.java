package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AccountNameUpdateRequest(
        @JsonProperty("account_name") String accountName) {
}
