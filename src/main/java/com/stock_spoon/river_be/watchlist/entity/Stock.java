package com.stock_spoon.river_be.watchlist.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "stocks", uniqueConstraints = @UniqueConstraint(
        name = "uk_stocks_market_stock_code", columnNames = {"market", "stock_code"}))
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_id")
    private Long id;
    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 10)
    private String market;
    @Column(length = 100)
    private String sector;

    protected Stock() {}

    public Stock(String stockCode, String name, String market, String sector) {
        this.stockCode = stockCode;
        this.name = name;
        this.market = market;
        this.sector = sector;
    }

    public Long getId() { return id; }
    public String getStockCode() { return stockCode; }
    public String getName() { return name; }
    public String getSector() { return sector; }
}