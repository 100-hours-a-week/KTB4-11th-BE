package com.stock_spoon.river_be.order.controller;

import com.stock_spoon.river_be.order.dto.OrderCancelRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateResponse;
import com.stock_spoon.river_be.order.dto.OrderHistoryResponse;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.execution.service.OrderBuyPriceService;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.order.service.OrderHistoryService;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;

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
    private final OrderHistoryService history;
    private final OrderService orders;
    private final OrderMarketValidator market;
    private final OrderSubscriptionService subscriptions;
    private final OrderExecutionListener execution;
    private final OrderExecutionService marketExecution;
    private final OrderBuyPriceService buyPrices;
    private final MarketStream stream;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.stock_spoon.river_be.order.messaging.OrderRequestService queuedRequests;

    // Spring이 생성자에 서비스 객체를 넣어 준다(의존성 주입).
    // orders.reserveLimit()을 따라갈 때는 orders의 타입인 OrderService를 연다.
    public OrderController(OrderService orders, OrderMarketValidator market,
            OrderSubscriptionService subscriptions, OrderExecutionListener execution, OrderHistoryService history,
            OrderExecutionService marketExecution, OrderBuyPriceService buyPrices, MarketStream stream) {
        this.history = history;
        this.orders = orders;
        this.market = market;
        this.subscriptions = subscriptions;
        this.execution = execution;
        this.marketExecution = marketExecution;
        this.buyPrices = buyPrices;
        this.stream = stream;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public OrderHistoryResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String page,
            @org.springframework.web.bind.annotation.RequestParam(name = "order_side", required = false) String orderSide,
            @org.springframework.web.bind.annotation.RequestParam(name = "order_status", required = false) String orderStatus,
            @org.springframework.web.bind.annotation.RequestParam(name = "stock_code", required = false) String stockCode) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        return history.list(userId, accountId, limit, page, orderSide, orderStatus, stockCode);
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


    // [1. 요청 입력] 인증된 JWT, URL의 계좌 ID, JSON 본문을 각각 인자로 받는다.
    // @RequestBody는 JSON을 DTO로 변환하고 @Valid는 DTO의 제약을 검사한다.
    // 반환 DTO는 Spring이 JSON으로 변환하며 @ResponseStatus에 따라 HTTP 201을 보낸다.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @Valid @RequestBody OrderCreateRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String eventId) {
        String actor = jwt.getClaimAsString("actor");
        Order.Source source;
        if ("AI".equals(actor)) {
            source = Order.Source.AI;
        } else if ("USER".equals(actor)) {
            source = Order.Source.USER;
        } else {
            throw new OrderException(HttpStatus.FORBIDDEN, "INVALID_ORDER_ACTOR",
                    "주문 생성 권한이 없습니다.");
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
        if (source == Order.Source.AI) {
            request.validateReportInputs(side);
        }
        if (marketOrder && request.limitPrice() != null) {
            throw new OrderException("시장가 주문의 지정가는 null이어야 합니다.");
        }
        if (!marketOrder && (request.limitPrice() == null || request.limitPrice() <= 0)) {
            throw new OrderException("지정가를 입력하세요.");
        }
        if (source == Order.Source.AI && (request.reason() == null || request.reason().isBlank())) {
            throw new OrderException("AI 주문의 판단 근거를 입력하세요.");
        }
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        if (queuedRequests != null) {
            if (source != Order.Source.USER) {
                throw new OrderException(HttpStatus.FORBIDDEN, "AI_ORDER_USE_QUEUE", "AI 주문은 주문 큐를 이용하세요.");
            }
            return org.springframework.http.ResponseEntity.accepted()
                    .body(queuedRequests.submit(userId, accountId, eventId, request));
        }
        // [2. 계좌 검사] 토큰의 사용자 ID로 계좌 소유권·활성 상태·AI 운용 여부를 확인한다.
        orders.assertOrderableAccount(userId, accountId, source);
        if (marketOrder) {
            Order order = createMarketOrder(userId, accountId, request, source, side);
            return execution.response(accountId, order.getId());
        }
        // 지정가는 시세 연결 여부와 무관하게 예약하고 커밋 후 시세를 구독한다.
        market.validateLimit(request.limitPrice());
        // 람다 () -> ... 는 지금 저장하지 않고 '나중에 실행할 저장 작업'을 전달한다.
        // OrderSubscriptionService.create()의 saveOrder.get()에서 reserveLimit()이 실행된다.
        Order order = subscriptions.create(request.stockCode(), () -> orders.reserveLimit(userId, accountId, request, source));
        // [4. 최초 체결 판단] 예약이 커밋된 주문을 현재가와 비교한다. 체결은 별도 트랜잭션이다.
        // 장외 예약은 새 장중 시세가 들어올 때까지 대기한다.
        execution.orderCreated(order);
        // [5. 응답] 주문·체결을 다시 읽어 즉시 체결됐으면 결과를, 대기 중이면 빈 체결 목록을 반환한다.
        return execution.response(accountId, order.getId());
    }

    private Order createMarketOrder(long userId, long accountId, OrderCreateRequest request,
            Order.Source source, Order.Side side) {
        var stage = new java.util.concurrent.atomic.AtomicReference<>("trading_time");
        try {
            if (!marketExecution.isTradingTime()) {
                throw new OrderException(HttpStatus.CONFLICT, "ORDER_WINDOW_CLOSED",
                        "시장가 주문은 한국 시간 평일 09:00부터 15:30 전까지 가능합니다.");
            }
            stage.set("quote_acquisition");
            var prices = source == Order.Source.AI && side == Order.Side.BUY
                    ? buyPrices.fetch(accountId, request.stockCode()) : java.util.Map.<String, java.math.BigDecimal>of();
            Order order = subscriptions.withMarketQuote(request.stockCode(), quote -> {
                stage.set("execution");
                Runnable validate = () -> {
                    try {
                        stream.assertSource(request.stockCode(), quote.source());
                    } catch (RuntimeException error) {
                        throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_STREAM_UNAVAILABLE",
                                "사용 가능한 최신 호가를 확인할 수 없습니다.");
                    }
                };
                return marketExecution.executeMarket(userId, accountId, request, source, quote.orderBook(), prices, validate);
            });
            log.info("event=market_order_completed orderId={} accountId={} stockCode={} side={} source={}",
                    order.getId(), accountId, request.stockCode(), side, source);
            return order;
        } catch (RuntimeException error) {
            String code = error instanceof OrderException rejection ? rejection.code() : "INTERNAL_ERROR";
            var level = error instanceof OrderException rejection && rejection.status().is4xxClientError()
                    ? log.atWarn() : log.atError();
            level.log("event=market_order_rejected stage={} code={} accountId={} stockCode={} side={} source={} causeType={}",
                    stage.get(), code, accountId, request.stockCode(), side, source, error.getClass().getSimpleName());
            throw error;
        }
    }
}
