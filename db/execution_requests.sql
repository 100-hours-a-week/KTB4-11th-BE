-- 공유 MySQL: trade_orders 생성 후, 체결 서버/BE 배포 전에 적용.
-- 기존 테이블/데이터는 변경하지 않는다. 자동 만료/삭제는 적용하지 않는다.
CREATE TABLE execution_requests (
    request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    payload_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id),
    UNIQUE KEY uk_execution_requests_order (order_id),
    CONSTRAINT fk_execution_requests_order FOREIGN KEY (order_id) REFERENCES trade_orders(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
