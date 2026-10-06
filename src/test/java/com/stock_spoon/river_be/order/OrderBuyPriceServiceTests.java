package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderBuyPriceServiceTests {
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final KiwoomMarketClient market = mock(KiwoomMarketClient.class);
    private final OrderBuyPriceService prices = new OrderBuyPriceService(holdings, market);

    private Holding holding(String code) {
        var holding = mock(Holding.class);
        when(holding.getStockCode()).thenReturn(code);
        return holding;
    }

    @Test
    void fetchesOtherStocksOnceOutsideTransactionAndSkipsBoughtStock() {
        doReturn(List.of(holding("005930"), holding("000660")))
                .when(holdings).findAllByAccountIds(List.of(1L));
        when(market.currentPrice("000660")).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return 10000L;
        });
        assertThat(prices.fetch(1, "005930")).containsOnlyKeys("000660");
        verify(market).currentPrice("000660");
        verifyNoMoreInteractions(market);
    }

    @Test
    void unavailableOrNonPositiveRestPriceReturnsNoValuationWithoutThrowing() {
        doReturn(List.of(holding("000660"))).when(holdings).findAllByAccountIds(List.of(1L));
        when(market.currentPrice("000660")).thenThrow(new IllegalStateException());
        assertThat(prices.fetch(1, "005930")).isEmpty();
        reset(market);
        when(market.currentPrice("000660")).thenReturn(0L);
        assertThat(prices.fetch(1, "005930")).isEmpty();
    }

    @Test
    void noOtherHoldingNeedsNoRestCall() {
        doReturn(List.of(holding("005930"))).when(holdings).findAllByAccountIds(List.of(1L));
        assertThat(prices.fetch(1, "005930")).isEmpty();
        verifyNoInteractions(market);
    }
}
