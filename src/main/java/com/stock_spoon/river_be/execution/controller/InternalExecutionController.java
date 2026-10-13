package com.stock_spoon.river_be.execution.controller;

import com.stock_spoon.river_be.execution.service.ExecutionRequestService;
import com.stock_spoon.river_be.execution.service.LocalExecutionGateway;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("execution")
@RequestMapping("/internal/execution")
public class InternalExecutionController {
    public record MarketCommand(@Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") @NotNull String requestId,
            @Positive long userId, @Positive long accountId, @Valid @NotNull OrderCreateRequest order) {}
    public record InitialCommand(@Positive long userId, @Positive long accountId, @Positive long orderId) {}
    public record Result(long orderId) {}
    private final LocalExecutionGateway gateway;
    private final ExecutionRequestService requests;
    private final OrderRepository orders;
    private final OrderMarketValidator validator;
    public InternalExecutionController(LocalExecutionGateway gateway, ExecutionRequestService requests,
            OrderRepository orders, OrderMarketValidator validator) {
        this.gateway = gateway; this.requests = requests; this.orders = orders; this.validator = validator;
    }
    public static String fingerprint(MarketCommand command) {
        try {
            var bytes = JsonMapper.builder().build().writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    @PostMapping("/market")
    public Result market(@Valid @RequestBody MarketCommand command) {
        var request = command.order();
        Order.Side side;
        try { side = Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new OrderException("매수·매도 구분을 확인하세요."); }
        request.validateReportInputs(side);
        if (!"market".equalsIgnoreCase(request.orderType()) || request.limitPrice() != null)
            throw new OrderException("시장가 입력을 확인하세요.");
        String hash = fingerprint(command);
        var existing = requests.result(command.requestId(), command.userId(), command.accountId(), hash);
        if (existing.isPresent()) return new Result(existing.get());
        validator.validateMarket(request.stockCode());
        var prepared = gateway.prepare(command.userId(), command.accountId(), request);
        long id = requests.execute(command.requestId(), command.userId(), command.accountId(), hash, () -> {
            long orderId = gateway.executePrepared(command.userId(), command.accountId(), request, prepared);
            return orders.findById(orderId).orElseThrow();
        });
        return new Result(id);
    }
    @PostMapping("/result")
    public Result result(@Valid @RequestBody MarketCommand command) {
        return new Result(requests.result(command.requestId(), command.userId(), command.accountId(), fingerprint(command))
                .orElseThrow(() -> new OrderException(HttpStatus.NOT_FOUND, "EXECUTION_RESULT_UNKNOWN", "처리 결과를 확인할 수 없습니다.")));
    }
    @PostMapping("/initial")
    public void initial(@Valid @RequestBody InitialCommand command) {
        gateway.initial(command.userId(), command.accountId(), command.orderId());
    }
}
