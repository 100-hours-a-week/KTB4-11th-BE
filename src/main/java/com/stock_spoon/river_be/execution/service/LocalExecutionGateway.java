package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.order.client.ExecutionGateway;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.market.MarketStream;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;

@Service
@Profile("!api")
public class LocalExecutionGateway implements ExecutionGateway {
    private final OrderExecutionService execution;
    private final OrderExecutionListener listener;
    private final OrderBuyPriceService prices;
    private final MarketStream stream;
    private final OrderRepository orders;
    private final OrderService orderService;
    public LocalExecutionGateway(OrderExecutionService execution, OrderExecutionListener listener,
            OrderBuyPriceService prices, MarketStream stream, OrderRepository orders, OrderService orderService) {
        this.execution = execution; this.listener = listener; this.prices = prices;
        this.stream = stream; this.orders = orders; this.orderService = orderService;
    }
    public long market(long userId, long accountId, OrderCreateRequest request) {
        return executePrepared(userId, accountId, request, prepare(userId, accountId, request));
    }
    public record Prepared(com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook book,
            java.util.Map<String, java.math.BigDecimal> prices, Runnable validate) {}
    public Prepared prepare(long userId, long accountId, OrderCreateRequest request) {
        orderService.assertOrderableAccount(userId, accountId);
        var values = "buy".equalsIgnoreCase(request.orderSide()) ? prices.fetch(accountId, request.stockCode())
                : java.util.Map.<String, java.math.BigDecimal>of();
        com.stock_spoon.river_be.market.RedisQuoteStore.QuoteEvent quote;
        try { quote = stream.latestBookQuote(request.stockCode()).orElse(null); }
        catch (RuntimeException error) {
            throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE", "사용 가능한 최신 호가를 확인할 수 없습니다.");
        }
        var book = quote == null ? null : quote.orderBook();
        Runnable validate = () -> {
            try {
                if (quote == null) stream.assertAvailable(request.stockCode());
                else stream.assertSource(request.stockCode(), quote.source());
            } catch (RuntimeException error) {
                throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE", "사용 가능한 최신 호가를 확인할 수 없습니다.");
            }
        };
        validate.run();
        return new Prepared(book, values, validate);
    }
    public long executePrepared(long userId, long accountId, OrderCreateRequest request, Prepared prepared) {
        return execution.executeMarket(userId, accountId, request, prepared.book(), prepared.prices(), prepared.validate()).getId();
    }
    public void initial(long userId, long accountId, long orderId) {
        orderService.assertOrderableAccount(userId, accountId);
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
        if (order.getStatus() == Order.Status.PENDING && order.getType() == Order.Type.LIMIT) listener.orderCreated(order);
    }
}
