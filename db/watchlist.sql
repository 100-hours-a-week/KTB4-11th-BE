-- MySQL 8.x. 실행 전 기존 stocks/watchlist_stocks 구조와 대조한다.
-- 이미 stocks가 있으면 CREATE는 건너뛴다. sector가 없으면 아래 ALTER만 별도로 적용한다.
-- ALTER TABLE stocks ADD COLUMN sector VARCHAR(100) NULL;
CREATE TABLE IF NOT EXISTS stocks (
    stock_id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    market VARCHAR(10) NOT NULL,
    sector VARCHAR(100) NULL,
    PRIMARY KEY (stock_id),
    UNIQUE KEY uk_stocks_market_symbol (market, symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS watchlist_stocks (
    user_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, stock_id),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_watchlist_stock FOREIGN KEY (stock_id) REFERENCES stocks (stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;