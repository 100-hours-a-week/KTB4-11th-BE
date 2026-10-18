package com.stock_spoon.river_be.watchlist.repository;

import com.stock_spoon.river_be.watchlist.entity.Watchlist;
import com.stock_spoon.river_be.watchlist.entity.WatchlistId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistRepository extends JpaRepository<Watchlist, WatchlistId> {
    @Query("select w from Watchlist w join fetch w.stock s where w.userId=:userId order by w.createdAt desc,s.id desc")
    List<Watchlist> list(@Param("userId") long userId);

    @Modifying
    @Query("delete from Watchlist w where w.userId=:userId and w.stock.id in (select s.id from Stock s where s.symbol=:code)")
    int remove(@Param("userId") long userId, @Param("code") String code);
}