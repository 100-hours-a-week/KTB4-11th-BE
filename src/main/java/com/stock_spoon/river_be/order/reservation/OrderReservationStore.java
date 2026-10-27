package com.stock_spoon.river_be.order.reservation;

import com.stock_spoon.river_be.order.messaging.OrderRequestMessage;
import java.time.Instant;
import java.util.List;

public interface OrderReservationStore {
    record Reservation(OrderRequestMessage message, long amount, Instant publishedAt) {}
    List<Reservation> list(long accountId);
    void add(Reservation reservation);
    void remove(long accountId, String eventId);
    // [계좌 비활성화 추가] 처리 결과를 DB에 남긴 계좌의 잔여 예약을 전체 해제한다.
    default void removeAll(long accountId) {
        for(var reservation:list(accountId)) remove(accountId,reservation.message().eventId());
    }
    void published(Reservation reservation, Instant at);
    List<Long> accounts();
}
