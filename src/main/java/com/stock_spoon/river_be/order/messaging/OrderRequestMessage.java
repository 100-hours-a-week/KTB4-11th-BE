package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record OrderRequestMessage(String eventId, String correlationId, long userId, long accountId,
        Order.Source source, Instant requestedAt, OrderCreateRequest request) {
    public OrderRequestMessage {
        if (eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,128}")
                || correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,128}")
                || userId <= 0 || accountId <= 0 || source == null || requestedAt == null || request == null) {
            throw new OrderException("주문 요청 식별 정보를 확인하세요.");
        }
    }
    public boolean market() { return "market".equalsIgnoreCase(request.orderType()); }
    public Order.Side side() { return Order.Side.valueOf(request.orderSide().toUpperCase(java.util.Locale.ROOT)); }
    public boolean expired(Instant now) {
        if (!market()) return false;
        var at = requestedAt.atZone(ZoneId.of("Asia/Seoul"));
        var current = now.atZone(at.getZone());
        return requestedAt.isAfter(now.plusSeconds(5))
                || at.getDayOfWeek()==java.time.DayOfWeek.SATURDAY || at.getDayOfWeek()==java.time.DayOfWeek.SUNDAY
                || at.toLocalTime().isBefore(LocalTime.of(9,0)) || !at.toLocalTime().isBefore(LocalTime.of(15,30))
                || !at.toLocalDate().equals(current.toLocalDate())
                || !current.toLocalTime().isBefore(LocalTime.of(15, 30));
    }
    public boolean sameRequest(OrderRequestMessage other) {
        return other != null && eventId.equals(other.eventId) && userId == other.userId
                && accountId == other.accountId && source == other.source && Objects.equals(request, other.request);
    }
}
