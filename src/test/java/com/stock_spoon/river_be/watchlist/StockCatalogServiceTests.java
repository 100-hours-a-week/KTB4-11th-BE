package com.stock_spoon.river_be.watchlist;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient.StockMaster;
import com.stock_spoon.river_be.watchlist.entity.Stock;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import com.stock_spoon.river_be.watchlist.service.StockCatalogService;
import com.stock_spoon.river_be.watchlist.service.WatchlistException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "kiwoom.stream.enabled=false")
class StockCatalogServiceTests {
    @Autowired StockCatalogService catalog;
    @Autowired StockRepository stocks;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean KiwoomMarketClient market;
    static final String CODE = "CAT001";

    @AfterEach void cleanup() { stocks.deleteAll(stocks.findAllByStockCode(CODE)); }

    @Test void databaseHitDoesNotQueryKiwoom() {
        var saved = stocks.save(new Stock(CODE, "기존", "KOSPI", null));
        assertThat(catalog.resolveStockId(CODE)).isEqualTo(saved.getId());
        verifyNoInteractions(market);
    }
    @Test void fetchesOutsideTransactionAndPersistsNullableSector() {
        when(market.stockMaster(CODE)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new StockMaster(CODE, "종목", "0", null);
        });
        long id = catalog.resolveStockId(CODE);
        assertThat(stocks.findById(id).orElseThrow().getSector()).isNull();
        assertThat(catalog.resolveStockId(CODE)).isEqualTo(id);
        verify(market).stockMaster(CODE);
    }
    @Test void rejectsAmbiguityWithoutProviderCall() {
        stocks.save(new Stock(CODE, "하나", "0", null));
        stocks.save(new Stock(CODE, "둘", "1", null));
        assertThatThrownBy(() -> catalog.resolveStockId(CODE)).isInstanceOfSatisfying(WatchlistException.class,
                error -> assertThat(error.code()).isEqualTo("AMBIGUOUS_STOCK_CODE"));
        verifyNoInteractions(market);
    }
    @Test void providerFailureDoesNotPersistOrBecomeNotFound() {
        when(market.stockMaster(CODE)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> catalog.resolveStockId(CODE)).isInstanceOfSatisfying(WatchlistException.class,
                error -> assertThat(error.code()).isEqualTo("STOCK_INFO_UNAVAILABLE"));
        assertThat(stocks.findAllByStockCode(CODE)).isEmpty();
    }
    @Test void concurrentMissesConvergeToOneCommittedMaster() throws Exception {
        var barrier = new CyclicBarrier(2);
        when(market.stockMaster(CODE)).thenAnswer(call -> {
            barrier.await(10, TimeUnit.SECONDS);
            return new StockMaster(CODE, "동시", "0", "업종");
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> catalog.resolveStockId(CODE));
            var second = executor.submit(() -> catalog.resolveStockId(CODE));
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(stocks.findAllByStockCode(CODE)).hasSize(1);
        }
    }

    @Test void unrelatedIntegrityFailureIsNotDisguisedAsSuccessfulRace() {
        var repository = mock(StockRepository.class);
        when(repository.findAllByStockCode(CODE)).thenReturn(java.util.List.of());
        when(market.stockMaster(CODE)).thenReturn(new StockMaster(CODE, "종목", "0", null));
        var failure = new org.springframework.dao.DataIntegrityViolationException("unrelated constraint");
        when(repository.saveAndFlush(any(Stock.class))).thenThrow(failure);
        when(repository.findByMarketAndStockCode("0", CODE)).thenReturn(java.util.Optional.empty());
        var service = new StockCatalogService(repository, market, manager);
        assertThatThrownBy(() -> service.resolveStockId(CODE)).isSameAs(failure);
    }
}
