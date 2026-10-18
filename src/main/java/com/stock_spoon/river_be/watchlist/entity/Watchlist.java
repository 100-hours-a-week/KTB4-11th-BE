package com.stock_spoon.river_be.watchlist.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "watchlist_stocks")
@IdClass(WatchlistId.class)
public class Watchlist {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Watchlist() {}

    public Watchlist(long userId, Stock stock) {
        this.userId = userId;
        this.stock = stock;
        this.createdAt = Instant.now();
    }

    public Stock getStock() { return stock; }
}