package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.entity.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("execution")
public class PendingExecutionRecovery {
    private final OrderRepository orders;
    private final OrderExecutionListener listener;
    public PendingExecutionRecovery(OrderRepository orders, OrderExecutionListener listener) {
        this.orders = orders; this.listener = listener;
    }
    @Scheduled(fixedDelay = 5000)
    public void recover() {
        for (var order : orders.findAllByStatus(Order.Status.PENDING)) {
            if (order.getType() == Order.Type.LIMIT) listener.recover(order);
        }
    }
}
