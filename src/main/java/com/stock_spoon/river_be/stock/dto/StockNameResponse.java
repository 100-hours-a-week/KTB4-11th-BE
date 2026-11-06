package com.stock_spoon.river_be.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StockNameResponse(
        @io.swagger.v3.oas.annotations.media.Schema(example = "005930")
        @JsonProperty("stock_code") String stockCode,
        @io.swagger.v3.oas.annotations.media.Schema(example = "삼성전자")
        @JsonProperty("stock_name") String stockName) {}
