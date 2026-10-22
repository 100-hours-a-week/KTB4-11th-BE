package com.stock_spoon.river_be.watchlist.repository;

import com.stock_spoon.river_be.watchlist.entity.Stock;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, Long> {
    List<Stock> findAllByStockCode(String stockCode);
}