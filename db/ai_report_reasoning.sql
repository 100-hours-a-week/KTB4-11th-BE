-- MySQL 8.4: apply before application deployment. Existing report names remain NULL.
ALTER TABLE ai_order_reports ADD COLUMN stock_name VARCHAR(255) NULL;
CREATE TABLE ai_report_reasoning (
    report_id BIGINT NOT NULL,
    reasoning_index INT NOT NULL,
    label VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    PRIMARY KEY (report_id, reasoning_index),
    CONSTRAINT fk_ai_report_reasoning_report FOREIGN KEY (report_id) REFERENCES ai_order_reports(report_id),
    CONSTRAINT ck_ai_report_reasoning_label CHECK (CHAR_LENGTH(TRIM(label)) > 0),
    CONSTRAINT ck_ai_report_reasoning_body CHECK (CHAR_LENGTH(TRIM(body)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
