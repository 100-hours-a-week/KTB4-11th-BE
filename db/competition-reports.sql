-- competitions 테이블 준비 후 한 번 적용한다. 실제 DB에는 자동 실행하지 않는다.
-- 대결별 AI 시장 분석과 판단 요약이며, 주문별 ai_order_reports와 별개다.
CREATE TABLE competition_market_analyses (
    analysis_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    analysis_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    INDEX ix_competition_analysis_date (competition_id, analysis_date),
    CONSTRAINT fk_competition_market_analysis FOREIGN KEY (competition_id) REFERENCES competitions(competition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE competition_judgment_summaries (
    summary_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    INDEX ix_competition_judgment (competition_id),
    CONSTRAINT fk_competition_judgment FOREIGN KEY (competition_id) REFERENCES competitions(competition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
