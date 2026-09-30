package com.stock_spoon.river_be.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {
    @org.springframework.data.jpa.repository.Query("select e from Execution e where e.order.id = :orderId order by e.id")
    java.util.List<Execution> findForOrder(@org.springframework.data.repository.query.Param("orderId") long orderId);
}