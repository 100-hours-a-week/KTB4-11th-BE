package com.stock_spoon.river_be.order.validator;

import com.stock_spoon.river_be.order.exception.OrderException;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderMarketValidatorTests {
    private final KiwoomMarketClient client = mock(KiwoomMarketClient.class);
    private final OrderMarketValidator validator = new OrderMarketValidator(client);

    @Test
    void validatesStockAndTickWithoutDependingOnMarketHours() {
        when(client.stockInfo("005930")).thenReturn(
                new KiwoomMarketClient.StockInfo("005930", "0", "증거금20%|담보대출|신용가능", "0"));
        validator.validateLimit("005930", 70_000);
    }

    @Test
    void requiresSupportedStockWithValidTick() {
        when(client.stockInfo("005930")).thenReturn(
                new KiwoomMarketClient.StockInfo("005930", "0", "증거금20%|담보대출|신용가능", "0"));
        validator.validateLimit("005930", 70_000);
        assertThatThrownBy(() -> validator.validateLimit("005930", 70_050))
                .isInstanceOf(OrderException.class);
        when(client.stockInfo("005930")).thenReturn(
                new KiwoomMarketClient.StockInfo("005930", "0", "거래정지", "0"));
        validator.validateLimit("005930", 70_000);
        when(client.stockInfo("005930")).thenReturn(
                new KiwoomMarketClient.StockInfo("005930", "10", "증거금20%|담보대출|신용가능", "0"));
        assertThatThrownBy(() -> validator.validateLimit("005930", 70_000))
                .isInstanceOf(OrderException.class);
    }

    @Test
    void etfHasSeparateTickSize() {
        when(client.stockInfo("069500")).thenReturn(
                new KiwoomMarketClient.StockInfo("069500", "8", "정상", "0"));
        validator.validateLimit("069500", 1_999);
        validator.validateLimit("069500", 2_000);
        validator.validateLimit("069500", 2_005);
        assertThatThrownBy(() -> validator.validateLimit("069500", 2_001))
                .isInstanceOf(OrderException.class);
    }

    @Test
    void rejectsEtnMarketCodes() {
        for (String code : new String[] {"60", "70", "90"}) {
            when(client.stockInfo("123456")).thenReturn(
                    new KiwoomMarketClient.StockInfo("123456", code, "정상", "0"));
            assertThatThrownBy(() -> validator.validateLimit("123456", 2_005))
                    .isInstanceOf(OrderException.class);
        }
    }

}
