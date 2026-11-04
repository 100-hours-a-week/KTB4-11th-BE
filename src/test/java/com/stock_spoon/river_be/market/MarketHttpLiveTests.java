package com.stock_spoon.river_be.market;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** 공개 시세 서버를 읽기만 한다. DB·Redis·주문 처리는 실행하지 않는다. */
@EnabledIfEnvironmentVariable(named = "MARKET_HTTP_LIVE_TEST", matches = "true")
class MarketHttpLiveTests {
    @Test void productionHttpClientReadsStockAndHistoricalAdjustedClose() {
        var client = new MarketHttpConfig().marketStockClient("https://stock-spoon.com/market");
        assertThat(client.stockInfo("005930")).containsEntry("code", "005930")
                .containsEntry("name", "삼성전자").containsEntry("marketCode", "0");
        assertThat(client.closingPrice("005930", LocalDate.of(2026, 11, 2))).isPositive();
    }
}
