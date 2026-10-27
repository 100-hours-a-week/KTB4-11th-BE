package com.stock_spoon.river_be.order.reservation;

import com.stock_spoon.river_be.order.messaging.OrderRequestMessage;
import java.time.Instant;
import java.util.List;

public interface OrderReservationStore {
    record Reservation(OrderRequestMessage message, long amount, Instant publishedAt) {}
    List<Reservation> list(long accountId);
    void add(Reservation reservation);
    void remove(long accountId, String eventId);
    // [회원 탈퇴 추가] 계좌 단위 전체 예약 삭제 계약이다. 기본 구현은 기존 단건 삭제를 재사용한다.
    default void removeAll(long accountId) {
        for (var reservation : list(accountId)) remove(accountId, reservation.message().eventId());
    }
    void published(Reservation reservation, Instant at);
    List<Long> accounts();
}
