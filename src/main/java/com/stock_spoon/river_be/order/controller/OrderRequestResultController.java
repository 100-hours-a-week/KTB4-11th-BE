package com.stock_spoon.river_be.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.messaging.OrderRequestService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "주문")
@RestController
@ConditionalOnProperty(name="order.queue.enabled",havingValue="true")
@RequestMapping({"/api/v1/accounts/{accountId}/order-requests", "/api/v3/accounts/{accountId}/order-requests"})
public class OrderRequestResultController {
    private final OrderRequestService service;
    public OrderRequestResultController(OrderRequestService service) { this.service=service; }
    @GetMapping("/{eventId}")
    @Operation(summary = "비동기 주문 처리 결과 조회")
    public Map<String,Object> result(@AuthenticationPrincipal Jwt jwt,@PathVariable long accountId,@PathVariable String eventId) {
        try { return service.result(Long.parseLong(jwt.getSubject()),accountId,eventId); }
        catch(NumberFormatException e) { throw new OrderException(HttpStatus.UNAUTHORIZED,"INVALID_TOKEN","인증 정보를 확인하세요."); }
    }
}
