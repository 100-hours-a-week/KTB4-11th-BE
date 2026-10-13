package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.dto.OrderCreateResponse;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final OrderRepository orders;
    private final ExecutionRepository executions;
    public OrderQueryService(OrderRepository orders, ExecutionRepository executions) {
        this.orders = orders; this.executions = executions;
    }
    @Transactional(readOnly = true)
    public com.stock_spoon.river_be.order.entity.Order order(long accountId, long orderId) {
        return orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
    }
    @Transactional(readOnly = true)
    public OrderCreateResponse response(long accountId, long orderId) {
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow();
        return OrderCreateResponse.from(order, executions.findForOrder(orderId));
    }
}
