-- 신규 설치용. competitions와 competition_participants 준비 후 한 번 적용한다. 자동 실행되지 않는다.
-- 기존 리포트 테이블은 competition-reports-participant.sql로 이관한다. 두 파일을 함께 실행하지 않는다.
ALTER TABLE competition_participants ADD COLUMN report_ready BOOLEAN NOT NULL DEFAULT FALSE;

-- AI 참가자별 시장 분석과 판단 요약이며, 주문별 ai_order_reports와 별개다.
CREATE TABLE competition_market_analyses (
    analysis_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    analysis_date DATE NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    INDEX ix_competition_analysis_date (competition_id, analysis_date),
    INDEX ix_competition_analysis_participant (competition_id, participant_id, analysis_date),
    CONSTRAINT fk_competition_market_analysis FOREIGN KEY (competition_id) REFERENCES competitions(competition_id),
    CONSTRAINT fk_competition_analysis_participant FOREIGN KEY (participant_id) REFERENCES competition_participants(participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE competition_judgment_summaries (
    summary_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    participant_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    INDEX ix_competition_judgment (competition_id),
    INDEX ix_competition_judgment_participant (competition_id, participant_id),
    CONSTRAINT fk_competition_judgment FOREIGN KEY (competition_id) REFERENCES competitions(competition_id),
    CONSTRAINT fk_competition_judgment_participant FOREIGN KEY (participant_id) REFERENCES competition_participants(participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
