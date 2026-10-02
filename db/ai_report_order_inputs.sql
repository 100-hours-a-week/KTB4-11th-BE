-- MySQL 8.4: apply once before deploying the new order request contract.
-- Existing reports keep NULL. No historical values are inferred.
ALTER TABLE ai_order_reports
    ADD COLUMN holding_weight_limit_percent DOUBLE NULL,
    ADD COLUMN is_lower_triggered BOOLEAN NULL,
    ADD CONSTRAINT ck_ai_report_weight_limit
        CHECK (holding_weight_limit_percent IS NULL
               OR holding_weight_limit_percent BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_ai_report_lower_triggered
        CHECK (is_lower_triggered IS NULL OR is_lower_triggered IN (0, 1));
