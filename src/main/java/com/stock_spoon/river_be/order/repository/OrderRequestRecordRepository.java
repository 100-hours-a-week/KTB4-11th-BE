package com.stock_spoon.river_be.order.repository;

import com.stock_spoon.river_be.order.entity.OrderRequestRecord;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRequestRecordRepository extends JpaRepository<OrderRequestRecord, String> {
    List<OrderRequestRecord> findByAccountIdAndEventIdIn(long accountId, Collection<String> eventIds);
}
