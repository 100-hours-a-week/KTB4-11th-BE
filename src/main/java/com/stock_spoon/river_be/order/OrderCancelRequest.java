package com.stock_spoon.river_be.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OrderCancelRequest(
        @NotNull @Pattern(regexp = "cancelled") String status) {}

