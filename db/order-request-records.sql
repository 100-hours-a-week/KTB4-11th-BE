-- Apply BEFORE deploying this code, even with ORDER_QUEUE_ENABLED=false (JPA validates all entities).
-- Existing trade_orders/accounts are unchanged.
CREATE TABLE order_request_records (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    order_id BIGINT NULL,
    reason_code VARCHAR(64) NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX ix_order_request_account (account_id)
);
