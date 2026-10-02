-- MySQL 8.4: apply once before deploying report weight snapshots.
-- Existing report weights remain NULL.
ALTER TABLE ai_order_reports
    ADD COLUMN holding_weight_after_trade_percent DOUBLE NULL,
    ADD CONSTRAINT ck_ai_report_holding_weight
        CHECK (holding_weight_after_trade_percent IS NULL
               OR holding_weight_after_trade_percent BETWEEN 0 AND 100);
