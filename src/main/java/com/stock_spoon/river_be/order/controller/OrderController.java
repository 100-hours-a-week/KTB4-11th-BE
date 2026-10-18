package com.stock_spoon.river_be.order.controller;

import com.stock_spoon.river_be.order.dto.OrderCancelRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.dto.OrderHistoryResponse;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.order.service.OrderHistoryService;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayAcceptedResponse;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final DbWriteReplayService writeReplay;

    // Spring이 생성자에 서비스 객체를 넣어 준다(의존성 주입).
    // orders.reserveLimit()을 따라갈 때는 orders의 타입인 OrderService를 연다.
    public OrderController(OrderService orders, OrderMarketValidator market,
            OrderSubscriptionService subscriptions, OrderExecutionListener execution, OrderHistoryService history,
            DbWriteReplayService writeReplay) {
        this.history = history;
        this.orders = orders;
        this.market = market;
        this.subscriptions = subscriptions;
        this.execution = execution;
        this.writeReplay = writeReplay;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public OrderHistoryResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String page,
            @org.springframework.web.bind.annotation.RequestParam(name = "order_side", required = false) String orderSide) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        return history.list(userId, accountId, limit, page, orderSide);
    }


    @org.springframework.web.bind.annotation.PatchMapping("/{orderId}")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long accountId,
            @PathVariable long orderId, @Valid @RequestBody OrderCancelRequest request) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException error) {
            throw new OrderException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "인증 정보를 확인하세요.");
        }
        try {
            orders.cancel(userId, accountId, orderId);
        } catch (RuntimeException failure) {
            var queued = writeReplay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.ORDER_CANCEL,
                    new DbWriteReplayService.OrderCancelPayload(userId, accountId, orderId));
            if (queued.isPresent()) {
                return ResponseEntity.accepted().body(DbWriteReplayAcceptedResponse.queued(queued.get()));
            }
            throw failure;
        }
        log.info("event=order_cancelled orderId={}", orderId);
        return ResponseEntity.noContent().build();
    }


    // [1. 요청 입력] 인증된 JWT, URL의 계좌 ID, JSON 본문을 각각 인자로 받는다.
    // @RequestBody는 JSON을 DTO로 변환하고 @Valid는 DTO의 제약을 검사한다.
    // 정상 저장은 HTTP 201, DB 전환 중 SQS에 보관한 요청은 HTTP 202로 응답한다.
    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId, @Valid @RequestBody OrderCreateRequest request) {
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
        if (marketOrder) {
            throw new OrderException(HttpStatus.BAD_REQUEST, "MARKET_ORDER_DISABLED", "시장가 주문은 지원하지 않습니다. 지정가 주문을 사용하세요.");
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
        if (request.limitPrice() == null || request.limitPrice() <= 0) {
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
        Order order;
        try {
            // [2. 계좌 검사] 토큰의 사용자 ID로 계좌 소유권·활성 상태·AI 운용 여부를 확인한다.
            orders.assertOrderableAccount(userId, accountId, source);
            // 지정가는 시세 연결 여부와 무관하게 예약하고 커밋 후 시세를 구독한다.
            market.validateLimit(request.stockCode(), request.limitPrice());
            // OrderSubscriptionService.create()의 saveOrder.get()에서 reserveLimit()이 실행된다.
            order = subscriptions.create(request.stockCode(), () -> orders.reserveLimit(userId, accountId, request, source));
        } catch (RuntimeException failure) {
            var queued = writeReplay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.ORDER_CREATE,
                    new DbWriteReplayService.OrderCreatePayload(userId, accountId, source, request));
            if (queued.isPresent()) {
                return ResponseEntity.accepted().body(DbWriteReplayAcceptedResponse.queued(queued.get()));
            }
            throw failure;
        }
        // [4. 최초 체결 판단] 예약이 커밋된 주문을 현재가와 비교한다. 체결은 별도 트랜잭션이다.
        // 장외 예약은 새 장중 시세가 들어올 때까지 대기한다.
        execution.orderCreated(order);
        // [5. 응답] 주문·체결을 다시 읽어 즉시 체결됐으면 결과를, 대기 중이면 빈 체결 목록을 반환한다.
        return ResponseEntity.status(HttpStatus.CREATED).body(execution.response(accountId, order.getId()));
    }
}
