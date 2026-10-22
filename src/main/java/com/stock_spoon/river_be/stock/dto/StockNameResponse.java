package com.stock_spoon.river_be.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StockNameResponse(
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("stock_name") String stockName) {}
