package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountCreateRequest(
        @JsonProperty("account_name") @Size(max = 20) String accountName,
        @JsonProperty("initial_capital")
        @NotNull @Min(1_000_000) @Max(100_000_000) Long initialCapital) {
}
