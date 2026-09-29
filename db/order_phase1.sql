-- 기존 accounts(account_id)에 연결되는 주문 1단계 테이블. MySQL 8.4.
CREATE TABLE holdings (
    holding_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    stock_code VARCHAR(6) NOT NULL,
    quantity BIGINT NOT NULL,
    total_cost DECIMAL(19,2) NOT NULL,
    CONSTRAINT uk_holdings_account_stock UNIQUE (account_id, stock_code),
    CONSTRAINT fk_holdings_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT ck_holdings_quantity CHECK (quantity > 0),
    CONSTRAINT ck_holdings_total_cost CHECK (total_cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trade_orders (
    order_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    stock_code VARCHAR(6) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    status VARCHAR(10) NOT NULL,
    order_source VARCHAR(8) NOT NULL,
    quantity BIGINT NOT NULL,
    limit_price BIGINT NULL,
    reserved_cash BIGINT NOT NULL DEFAULT 0,
    decision_id VARCHAR(100) NULL,
    decision_summary VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    KEY ix_orders_account_status (account_id, status),
    CONSTRAINT fk_orders_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT ck_orders_quantity CHECK (quantity > 0),
    CONSTRAINT ck_orders_price CHECK ((order_type = 'LIMIT' AND limit_price > 0)
        OR (order_type = 'MARKET' AND limit_price IS NULL)),
    CONSTRAINT ck_orders_reserved CHECK (reserved_cash >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
