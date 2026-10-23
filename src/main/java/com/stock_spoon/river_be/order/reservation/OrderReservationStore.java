package com.stock_spoon.river_be.order.reservation;

import com.stock_spoon.river_be.order.messaging.OrderRequestMessage;
import java.time.Instant;
import java.util.List;

public interface OrderReservationStore {
    record Reservation(OrderRequestMessage message, long amount, Instant publishedAt) {}
    List<Reservation> list(long accountId);
    void add(Reservation reservation);
    void remove(long accountId, String eventId);
    void published(Reservation reservation, Instant at);
    List<Long> accounts();
}
