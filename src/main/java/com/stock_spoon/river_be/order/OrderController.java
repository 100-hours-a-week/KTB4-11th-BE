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
    private final OrderService orders;
    private final OrderMarketValidator market;
    private final OrderSubscriptionService subscriptions;
    private final OrderExecutionListener execution;

    public OrderController(OrderService orders, OrderMarketValidator market,
            OrderSubscriptionService subscriptions, OrderExecutionListener execution) {
        this.orders = orders;
        this.market = market;
        this.subscriptions = subscriptions;
        this.execution = execution;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderCreateResponse create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @Valid @RequestBody OrderCreateRequest request) {
        if (!"AI".equals(jwt.getClaimAsString("actor"))) {
            throw new OrderException(HttpStatus.FORBIDDEN, "AI_ORDER_ONLY",
                    "AI 서버만 주문을 생성할 수 있습니다.");
        }
        if (!"limit".equalsIgnoreCase(request.orderType())) {
            throw new OrderException("현재는 지정가 주문만 지원합니다.");
        }
        Order.Side side;
        try {
            side = Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new OrderException("매수·매도 구분을 확인하세요.");
        }
        if (request.limitPrice() == null || request.limitPrice() <= 0) {
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
        market.validateLimit(request.stockCode(), request.limitPrice());
        Order order = subscriptions.create(request.stockCode(), () -> orders.reserveLimit(userId, accountId, request.stockCode(), side,
                request.quantity(), request.limitPrice(), request.reason()));
        execution.orderCreated(order);
        return execution.response(accountId, order.getId());
    }
}
