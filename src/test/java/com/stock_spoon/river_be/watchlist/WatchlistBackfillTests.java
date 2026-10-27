package com.stock_spoon.river_be.watchlist;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.StockMaster;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.watchlist.entity.Stock;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import com.stock_spoon.river_be.watchlist.repository.WatchlistRepository;
import com.stock_spoon.river_be.watchlist.service.WatchlistService;
import com.stock_spoon.river_be.watchlist.service.WatchlistException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "kiwoom.stream.enabled=false")
class WatchlistBackfillTests {
    @Autowired UserRepository users;
    @Autowired StockRepository stocks;
    @Autowired WatchlistRepository watchlists;
    @Autowired WatchlistService service;
    @MockitoBean KiwoomMarketClient market;
    List<Long> ids = new ArrayList<>();
    static final String CODE = "NEW001";
    long user() { long id = users.save(new User("관심 적재")).getId(); ids.add(id); return id; }
    @AfterEach void cleanup() {
        for (long id : ids) {
            watchlists.deleteAll(watchlists.list(id));
            if (users.existsById(id)) users.deleteById(id);
        }
        for (String code : List.of(CODE, "OLD001")) stocks.deleteAll(stocks.findAllByStockCode(code));
    }

    @Test void sameUserConcurrentMissReturnsCreatedAndDuplicate() throws Exception {
        concurrent(user(), false);
    }
    @Test void differentUsersConcurrentMissShareOneMaster() throws Exception {
        concurrent(user(), true);
    }
    void concurrent(long firstUser, boolean different) throws Exception {
        long secondUser = different ? user() : firstUser;
        var barrier = new CyclicBarrier(2);
        when(market.stockMaster(CODE)).thenAnswer(call -> {
            barrier.await(10, TimeUnit.SECONDS);
            return new StockMaster(CODE, "새 종목", "0", null);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.add(firstUser, CODE));
            var second = executor.submit(() -> service.add(secondUser, CODE));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, different);
            assertThat(stocks.findAllByStockCode(CODE)).hasSize(1);
            assertThat(service.list(firstUser).watchlists()).hasSize(1);
            assertThat(service.list(secondUser).watchlists()).hasSize(1);
        }
    }

    @Test void slowProviderDoesNotLockUserAndDeletedUserCannotRegister() throws Exception {
        long id = user();
        stocks.save(new Stock("OLD001", "기존", "0", null));
        service.add(id, "OLD001");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(market.stockMaster(CODE)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            return new StockMaster(CODE, "새 종목", "0", null);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var adding = executor.submit(() -> service.add(id, CODE));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> service.remove(id, "OLD001")).get(5, TimeUnit.SECONDS);
                users.deleteById(id);
            } finally { release.countDown(); }
            assertThatThrownBy(() -> adding.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                    error -> assertThat(error.getCause()).isInstanceOfSatisfying(WatchlistException.class,
                            rejected -> assertThat(rejected.code()).isEqualTo("UNAUTHORIZED")));
            assertThat(stocks.findAllByStockCode(CODE)).hasSize(1);
            assertThat(watchlists.list(id)).isEmpty();
        }
    }

    @Test void missingUserDoesNotQueryProvider() {
        long id = user(); users.deleteById(id);
        assertThatThrownBy(() -> service.add(id, CODE)).isInstanceOf(WatchlistException.class);
        verifyNoInteractions(market);
    }
}
