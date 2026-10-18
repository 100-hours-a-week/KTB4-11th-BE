package com.stock_spoon.river_be.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WatchlistAddResponse(String message, @JsonProperty("stock_code") String stockCode) {
}