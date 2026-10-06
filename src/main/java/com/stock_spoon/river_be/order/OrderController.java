package com.stock_spoon.river_be.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/orders")
public class OrderController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderController.class);
    private final OrderBuyPriceService buyPrices;
    private final OrderExecutionService marketExecution;
    private final com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream stream;
    private final OrderHistoryService history;
    private final OrderService orders;
    private final OrderMarketValidator market;
    private final OrderSubscriptionService subscriptions;
    private final OrderExecutionListener execution;

    public OrderController(OrderService orders, OrderMarketValidator market,
            OrderSubscriptionService subscriptions, OrderExecutionListener execution, OrderHistoryService history,
            OrderExecutionService marketExecution,
            com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream stream, OrderBuyPriceService buyPrices) {
        this.buyPrices = buyPrices;
        this.marketExecution = marketExecution;
        this.stream = stream;
        this.history = history;
        this.orders = orders;
        this.market = market;
        this.subscriptions = subscriptions;
        this.execution = execution;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public OrderHistoryResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String limit) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        return history.list(userId, accountId, limit);
    }


    @org.springframework.web.bind.annotation.PatchMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @PathVariable long orderId, @Valid @RequestBody OrderCancelRequest request) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        orders.cancel(userId, accountId, orderId);
        log.info("event=order_cancelled orderId={}", orderId);
    }


    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @Valid @RequestBody OrderCreateRequest request) {
        if (!"AI".equals(jwt.getClaimAsString("actor"))) {
            throw new OrderException(HttpStatus.FORBIDDEN, "AI_ORDER_ONLY",
                    "AI 서버만 주문을 생성할 수 있습니다.");
        }
        boolean marketOrder = "market".equalsIgnoreCase(request.orderType());
        if (!marketOrder && !"limit".equalsIgnoreCase(request.orderType())) {
            throw new OrderException("주문 유형을 확인하세요.");
        }
        Order.Side side;
        try {
            side = Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new OrderException("매수·매도 구분을 확인하세요.");
        }
        request.validateReportInputs(side);
        if (marketOrder && request.limitPrice() != null) {
            throw new OrderException("시장가 주문의 지정가는 null이어야 합니다.");
        }
        if (!marketOrder && (request.limitPrice() == null || request.limitPrice() <= 0)) {
            throw new OrderException("지정가를 입력하세요.");
        }
        if (request.reason() == null) {
            throw new OrderException("AI 주문의 판단 근거를 입력하세요.");
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        orders.assertOrderableAccount(userId, accountId);
        if (marketOrder) {
            market.validateMarket(request.stockCode());
            var prices = side == Order.Side.BUY ? buyPrices.fetch(accountId, request.stockCode())
                    : java.util.Map.<String, java.math.BigDecimal>of();
            var order = subscriptions.create(request.stockCode(), () -> {
                var book = stream.latestOrderBook(request.stockCode()).orElse(null);
                return marketExecution.executeMarket(userId, accountId, request, book, prices);
            });
            return execution.response(accountId, order.getId());
        }
        market.validateLimit(request.stockCode(), request.limitPrice());
        Order order = subscriptions.create(request.stockCode(), () -> orders.reserveLimit(userId, accountId, request));
        execution.orderCreated(order);
        return execution.response(accountId, order.getId());
    }
}
