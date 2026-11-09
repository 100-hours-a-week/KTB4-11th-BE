-- 수동 적용용. 자동 실행하지 않음. BE 배포 전 대상 DB에 적용.
CREATE TABLE competition_ai_setup_outbox (
 outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 competition_id BIGINT NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 status VARCHAR(16) NOT NULL,
 payload LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_ai_setup_outbox_competition UNIQUE (competition_id),
 CONSTRAINT uk_ai_setup_outbox_request UNIQUE (event_id),
 CONSTRAINT fk_ai_setup_outbox_competition FOREIGN KEY (competition_id) REFERENCES competitions(competition_id),
 INDEX idx_ai_setup_outbox_status_id (status, outbox_id)
);
