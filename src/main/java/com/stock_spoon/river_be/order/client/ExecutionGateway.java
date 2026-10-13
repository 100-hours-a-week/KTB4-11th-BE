package com.stock_spoon.river_be.order.client;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;

public interface ExecutionGateway {
    long market(long userId, long accountId, OrderCreateRequest request);
    void initial(long userId, long accountId, long orderId);
}
