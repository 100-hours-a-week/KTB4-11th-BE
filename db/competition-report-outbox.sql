-- 신규 설치용. 기존 테이블은 competition-report-outbox-participant.sql로 이관한다. 자동 실행되지 않는다.
CREATE TABLE competition_report_outbox (
    outbox_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_report_outbox_status_id (status, outbox_id),
    CONSTRAINT uk_report_outbox_competition_participant UNIQUE (competition_id, participant_id),
    CONSTRAINT uk_report_outbox_request UNIQUE (request_id),
    CONSTRAINT fk_report_outbox_competition FOREIGN KEY (competition_id) REFERENCES competitions(competition_id),
    CONSTRAINT fk_report_outbox_participant FOREIGN KEY (participant_id) REFERENCES competition_participants(participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
