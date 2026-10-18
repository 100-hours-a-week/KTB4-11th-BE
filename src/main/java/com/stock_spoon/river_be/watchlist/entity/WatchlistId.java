package com.stock_spoon.river_be.watchlist.entity;

import java.io.Serializable;
import java.util.Objects;

public class WatchlistId implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private Long stock;

    public WatchlistId() {}

    public WatchlistId(Long userId, Long stock) {
        this.userId = userId;
        this.stock = stock;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WatchlistId id
                && Objects.equals(userId, id.userId) && Objects.equals(stock, id.stock);
    }

    @Override
    public int hashCode() { return Objects.hash(userId, stock); }
}