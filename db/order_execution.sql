-- 주문·체결 정책 v1: Order 1:N Execution. 기존 trade_orders 생성 후 적용한다.
CREATE TABLE executions (
    execution_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    execution_price BIGINT NOT NULL,
    execution_quantity BIGINT NOT NULL,
    realized_pnl DECIMAL(19,2) NULL,
    realized_return_percent DECIMAL(19,4) NULL,
    created_at DATETIME(6) NOT NULL,
    KEY ix_executions_order (order_id),
    CONSTRAINT fk_executions_order FOREIGN KEY (order_id) REFERENCES trade_orders(order_id),
    CONSTRAINT ck_executions_price CHECK (execution_price > 0),
    CONSTRAINT ck_executions_quantity CHECK (execution_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;