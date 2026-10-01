-- MySQL 8.4: deploy before the new application. Existing columns are retained for rollback.
CREATE TABLE ai_order_reports (
    report_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    reason MEDIUMTEXT NOT NULL,
    CONSTRAINT uk_ai_report_order UNIQUE (order_id),
    CONSTRAINT fk_ai_report_order FOREIGN KEY (order_id) REFERENCES trade_orders(order_id),
    CONSTRAINT ck_ai_report_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0 AND CHAR_LENGTH(reason) <= 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve existing AI summaries; orders without a reason have no report.
INSERT INTO ai_order_reports (order_id, reason)
SELECT order_id, decision_summary FROM trade_orders
WHERE order_source = 'AI' AND decision_summary IS NOT NULL AND CHAR_LENGTH(TRIM(decision_summary)) > 0;
