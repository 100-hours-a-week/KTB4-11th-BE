package com.stock_spoon.river_be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AccountCreateRequest(
        @io.swagger.v3.oas.annotations.media.Schema(description = "계좌명, 최대 20자", example = "기본 계좌")
        @JsonProperty("account_name") @Size(max = 20) String accountName,
        @io.swagger.v3.oas.annotations.media.Schema(description = "시작 자본금, 100만~1억 원", example = "10000000")
        @JsonProperty("initial_capital")
        @NotNull @Min(1_000_000) @Max(100_000_000) Long initialCapital) {
}
