package com.stock_spoon.river_be.watchlist.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WatchlistResponse(String message, List<Item> watchlists) {
    public record Item(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("stock_name") String stockName,
            String sector) {
    }
}