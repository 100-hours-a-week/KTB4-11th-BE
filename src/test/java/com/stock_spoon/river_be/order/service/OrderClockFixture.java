package com.stock_spoon.river_be.order.service;

public final class OrderClockFixture {
    private OrderClockFixture() {}
    public static OrderService create(com.stock_spoon.river_be.account.repository.AccountRepository accounts,
            com.stock_spoon.river_be.order.repository.OrderRepository orders,
            com.stock_spoon.river_be.holding.repository.HoldingRepository holdings, java.time.Clock clock) {
        return new OrderService(accounts, orders, holdings, clock);
    }
}
