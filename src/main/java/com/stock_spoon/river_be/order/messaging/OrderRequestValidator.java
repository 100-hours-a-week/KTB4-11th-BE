package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import jakarta.validation.Validator;

public class OrderRequestValidator {
    private final Validator validator;
    private final OrderMarketValidator prices;
    public OrderRequestValidator(Validator validator, OrderMarketValidator prices) {
        this.validator = validator; this.prices = prices;
    }
    public void validate(OrderRequestMessage message) {
        var request = message.request();
        if (!validator.validate(request).isEmpty()) throw new OrderException("주문 입력값을 확인하세요.");
        try { message.side(); } catch (IllegalArgumentException e) { throw new OrderException("매수·매도 구분을 확인하세요."); }
        if (!message.market() && !"limit".equalsIgnoreCase(request.orderType())) throw new OrderException("주문 유형을 확인하세요.");
        if (message.market()) {
            if (request.limitPrice() != null) throw new OrderException("시장가 주문의 지정가는 null이어야 합니다.");
        } else {
            if (request.limitPrice() == null) throw new OrderException("지정가를 입력하세요.");
            prices.validateLimit(request.limitPrice());
        }
        if (message.source() == Order.Source.AI) {
            request.validateReportInputs(message.side());
            if (request.reason() == null || request.reason().isBlank()) throw new OrderException("AI 주문의 판단 근거를 입력하세요.");
        }
        new OrderRequestCodec(tools.jackson.databind.json.JsonMapper.builder().build()).encode(message);
    }
}
