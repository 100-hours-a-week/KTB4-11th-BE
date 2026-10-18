package com.stock_spoon.river_be.watchlist.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "stocks", uniqueConstraints = @UniqueConstraint(
        name = "uk_stocks_market_symbol", columnNames = {"market", "symbol"}))
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_id")
    private Long id;
    @Column(nullable = false, length = 20)
    private String symbol;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 10)
    private String market;
    @Column(length = 100)
    private String sector;

    protected Stock() {}

    public Stock(String symbol, String name, String market, String sector) {
        this.symbol = symbol;
        this.name = name;
        this.market = market;
        this.sector = sector;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public String getSector() { return sector; }
}