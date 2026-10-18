package com.stock_spoon.river_be.watchlist;

import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.watchlist.entity.Stock;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import com.stock_spoon.river_be.watchlist.service.WatchlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"AUTH_CSRF_DISABLED=false", "kakao.frontend-origin=http://localhost:3000", "kiwoom.stream.enabled=false"})
class WatchlistConcurrencyTests {
    @Autowired UserRepository users;
    @Autowired StockRepository stocks;
    @Autowired WatchlistService service;

    @Test void concurrentAddsCreateOneRowAndOnlyOneCreatedResult() throws Exception {
        var user = users.save(new User("동시 등록"));
        var stock = stocks.save(new Stock("12345A", "동시 테스트", "KOSPI", null));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> add = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return service.add(user.getId(), "12345A");
            };
            var first = executor.submit(add);
            var second = executor.submit(add);
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(service.list(user.getId()).watchlists()).hasSize(1);
        } finally {
            service.remove(user.getId(), "12345A");
            stocks.delete(stock);
            users.delete(user);
        }
    }
}