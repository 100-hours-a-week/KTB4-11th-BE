-- 기존 대결 테이블 준비 후 한 번 적용한다. 신규 DB는 competition.sql 다음에 적용한다.
-- 실제 DB에 자동 실행하지 않는다.
CREATE TABLE competition_daily_performances (
    performance_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    participant_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    asset_value BIGINT NOT NULL,
    cumulative_return_percent DECIMAL(19,4) NOT NULL,
    UNIQUE KEY uk_performance_participant_date (participant_id, record_date),
    CONSTRAINT fk_daily_performance_participant FOREIGN KEY (participant_id)
        REFERENCES competition_participants(participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
