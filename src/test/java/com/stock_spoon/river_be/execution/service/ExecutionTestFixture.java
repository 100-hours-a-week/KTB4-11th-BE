package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import java.time.Clock;

/** 다른 도메인의 통합 테스트에서도 체결 시각을 고정한다. */
public final class ExecutionTestFixture {
    private ExecutionTestFixture() {}

    public static OrderExecutionService withClock(AccountRepository accounts, OrderRepository orders,
            HoldingRepository holdings, ExecutionRepository executions, Clock clock) {
        return new OrderExecutionService(accounts, orders, holdings, executions, clock);
    }
}
