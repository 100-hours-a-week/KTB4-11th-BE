package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 체결 트랜잭션에 들어가기 전에 다른 보유 종목의 평가용 REST 가격을 확보한다. */
@Service
public class OrderBuyPriceService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderBuyPriceService.class);
    private final HoldingRepository holdings;
    private final KiwoomMarketClient market;

    public OrderBuyPriceService(HoldingRepository holdings, KiwoomMarketClient market) {
        this.holdings = holdings;
        this.market = market;
    }

    public Map<String, BigDecimal> fetch(long accountId, String boughtStock) {
        var prices = new HashMap<String, BigDecimal>();
        for (var holding : holdings.findAllByAccountIds(List.of(accountId))) {
            String code = holding.getStockCode();
            if (code.equals(boughtStock)) continue;
            try {
                long price = market.currentPrice(code);
                if (price <= 0) throw new IllegalStateException();
                prices.put(code, BigDecimal.valueOf(price));
            } catch (IllegalStateException error) {
                log.warn("event=order_buy_weight_price_failed accountId={} stockCode={} causeType={}",
                        accountId, code, error.getClass().getSimpleName());
                return Map.of();
            }
        }
        return Map.copyOf(prices);
    }
}
