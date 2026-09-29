package com.stock_spoon.river_be.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrderCreateRequest(
        @JsonProperty("stock_code") @NotBlank @Pattern(regexp = "[0-9]{6}") String stockCode,
        @JsonProperty("order_side") @NotBlank String orderSide,
        @JsonProperty("order_type") @NotBlank String orderType,
        @JsonProperty("limit_price") Long limitPrice,
        @NotNull @Min(1) Long quantity,
        @Valid Reason reason) {

    public record Reason(
            @JsonProperty("decision_id") @NotBlank @Size(max = 100) String decisionId,
            @NotBlank @Size(max = 500) String summary) {}
}
