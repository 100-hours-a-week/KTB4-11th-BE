-- MySQL 8.x: 기존 symbol 컬럼이 있는 DB에 한 번만 수동 적용한다.
-- 애플리케이션을 중지하고 백업·SHOW CREATE TABLE stocks 확인 후 실행한다.
-- 신규 DB에는 watchlist.sql만 적용한다. Hibernate update는 rename을 처리하지 않는다.
-- stock_id와 기존 값, watchlist_stocks FK는 유지된다.
ALTER TABLE stocks
    RENAME COLUMN symbol TO stock_code,
    RENAME INDEX uk_stocks_market_symbol TO uk_stocks_market_stock_code;
