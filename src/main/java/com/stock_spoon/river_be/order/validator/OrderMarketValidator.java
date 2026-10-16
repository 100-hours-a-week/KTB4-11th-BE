package com.stock_spoon.river_be.order.validator;

import com.stock_spoon.river_be.order.exception.OrderException;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 키움 종목정보·호가단위를 확인한다. 실제 주문은 키움에 보내지 않는다. */
@Component
public class OrderMarketValidator {
    private static final Set<String> SUPPORTED_MARKETS = Set.of("0", "8");
    private final KiwoomMarketClient market;

    public OrderMarketValidator(KiwoomMarketClient market) {
        this.market = market;
    }

    public void validateLimit(String stockCode, long limitPrice) {
        var info = validateMarket(stockCode);
        long tick = tickSize(limitPrice, "8".equals(info.marketCode()));
        if (limitPrice <= 0 || limitPrice % tick != 0) {
            throw new OrderException("지정가가 호가가격단위에 맞지 않습니다.");
        }
    }

    public KiwoomMarketClient.StockInfo validateMarket(String stockCode) {
        KiwoomMarketClient.StockInfo info;
        try {
            info = market.stockInfo(stockCode);
        } catch (IllegalStateException error) {
            throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_DATA_UNAVAILABLE",
                    "종목 정보를 확인할 수 없습니다.");
        }
        if (!SUPPORTED_MARKETS.contains(info.marketCode())) {
            throw new OrderException("지원하는 주문 대상 종목이 아닙니다.");
        }
        return info;
    }

    static long tickSize(long price, boolean etp) {
        if (etp) return price < 2_000 ? 1 : 5;
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }
}
