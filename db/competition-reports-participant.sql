-- 기존 MySQL 대결 보고서 이관용. 신규 competition-reports.sql과 함께 실행하지 않는다.
-- 운영에 자동 적용하지 않는다. 먼저 백업하고 모든 BE 인스턴스의 관련 쓰기를 중지한다.
-- 보고서 POST 수신·종료/전송 스케줄러·회원 탈퇴 등을 포함한다. 전송만 끄는 것으로 충분하지 않다.
-- AI 콜백은 새 계약(participant_id 필수) 배포 후 재시도할 수 있도록 AI 파트와 조율한다.
-- MySQL DDL은 암묵적으로 커밋된다. 각 단계별 결과를 확인하며 한 번만 적용한다.
-- 기존 competitions.report_ready는 먼저 존재해야 하며 이 파일에서 삭제하지 않는다.

-- 1. 대상 참가자 연결과 참가자별 수신 완료 상태를 추가한다.
ALTER TABLE competition_participants ADD COLUMN report_ready BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE competition_market_analyses ADD COLUMN participant_id BIGINT NULL;
ALTER TABLE competition_judgment_summaries ADD COLUMN participant_id BIGINT NULL;

-- 2. 대결에 AI가 정확히 한 명인 기존 데이터만 자동 연결한다.
-- 단순 MIN으로 여러 AI 중 한 명을 선택하지 않는다. 제목·내용·날짜·기존 PK는 그대로 보존한다.
UPDATE competition_market_analyses r
JOIN competitions c ON c.competition_id = r.competition_id
JOIN (
    SELECT p.competition_id, MIN(p.participant_id) AS participant_id, MIN(a.user_id) AS user_id
    FROM competition_participants p JOIN accounts a ON a.account_id = p.account_id
    WHERE a.is_ai_managed = TRUE
    GROUP BY p.competition_id HAVING COUNT(*) = 1
) ai ON ai.competition_id = r.competition_id AND ai.user_id = c.user_id
SET r.participant_id = ai.participant_id WHERE r.participant_id IS NULL;

UPDATE competition_judgment_summaries r
JOIN competitions c ON c.competition_id = r.competition_id
JOIN (
    SELECT p.competition_id, MIN(p.participant_id) AS participant_id, MIN(a.user_id) AS user_id
    FROM competition_participants p JOIN accounts a ON a.account_id = p.account_id
    WHERE a.is_ai_managed = TRUE
    GROUP BY p.competition_id HAVING COUNT(*) = 1
) ai ON ai.competition_id = r.competition_id AND ai.user_id = c.user_id
SET r.participant_id = ai.participant_id WHERE r.participant_id IS NULL;

-- 3. 반드시 두 조회가 0건인지 확인한다. 미연결·다른 대결·사용자 계좌는 수동 확인 후 보정한다.
SELECT r.analysis_id, r.competition_id, r.participant_id
FROM competition_market_analyses r
LEFT JOIN competition_participants p ON p.participant_id = r.participant_id
LEFT JOIN accounts a ON a.account_id = p.account_id
LEFT JOIN competitions c ON c.competition_id = r.competition_id
WHERE p.participant_id IS NULL OR p.competition_id <> r.competition_id
   OR a.is_ai_managed = FALSE OR a.user_id <> c.user_id;

SELECT r.summary_id, r.competition_id, r.participant_id
FROM competition_judgment_summaries r
LEFT JOIN competition_participants p ON p.participant_id = r.participant_id
LEFT JOIN accounts a ON a.account_id = p.account_id
LEFT JOIN competitions c ON c.competition_id = r.competition_id
WHERE p.participant_id IS NULL OR p.competition_id <> r.competition_id
   OR a.is_ai_managed = FALSE OR a.user_id <> c.user_id;

-- 4. 본문이 있는 참가자는 완료로 보존한다. 빈 본문도 구버전 완료 표시가 있고 단일 AI이면 완료다.
UPDATE competition_participants p
JOIN accounts a ON a.account_id = p.account_id
JOIN competitions c ON c.competition_id = p.competition_id
LEFT JOIN (
    SELECT candidate.competition_id, COUNT(*) AS ai_count
    FROM competition_participants candidate JOIN accounts acc ON acc.account_id = candidate.account_id
    WHERE acc.is_ai_managed = TRUE GROUP BY candidate.competition_id
) counts ON counts.competition_id = p.competition_id
SET p.report_ready = TRUE
WHERE a.is_ai_managed = TRUE AND a.user_id = c.user_id
  AND (EXISTS (SELECT 1 FROM competition_market_analyses r WHERE r.participant_id = p.participant_id AND r.competition_id = p.competition_id)
       OR EXISTS (SELECT 1 FROM competition_judgment_summaries r WHERE r.participant_id = p.participant_id AND r.competition_id = p.competition_id)
       OR (c.report_ready = TRUE AND counts.ai_count = 1));

-- 5. 구버전 완료 표시는 있는데 대상 AI를 확인하지 못한 대결도 0건인지 확인한다.
-- 결과가 있으면 원본 보고서/수신 이력으로 올바른 참가자를 확인하고 수동 보정한다.
-- 내용도 없고 구버전 완료 표시도 없던 과거 보고서는 완료 여부를 추측하지 않는다.
SELECT c.competition_id
FROM competitions c
WHERE c.report_ready = TRUE AND NOT EXISTS (
    SELECT 1 FROM competition_participants p JOIN accounts a ON a.account_id = p.account_id
    WHERE p.competition_id = c.competition_id AND p.report_ready = TRUE
      AND a.is_ai_managed = TRUE AND a.user_id = c.user_id
);

-- 6. 위 검증 조회가 모두 0건일 때만 최종 제약을 적용한다.
ALTER TABLE competition_market_analyses
    MODIFY COLUMN participant_id BIGINT NOT NULL,
    ADD INDEX ix_competition_analysis_participant (competition_id, participant_id, analysis_date),
    ADD CONSTRAINT fk_competition_analysis_participant FOREIGN KEY (participant_id)
        REFERENCES competition_participants(participant_id);
ALTER TABLE competition_judgment_summaries
    MODIFY COLUMN participant_id BIGINT NOT NULL,
    ADD INDEX ix_competition_judgment_participant (competition_id, participant_id),
    ADD CONSTRAINT fk_competition_judgment_participant FOREIGN KEY (participant_id)
        REFERENCES competition_participants(participant_id);

-- 7. 신버전만 기동하고 POST participant_id와 GET reports 배열을 확인한다.
-- 신버전은 competitions.report_ready를 갱신하지 않으므로 구버전과 혼용하거나 단순 앱 롤백하지 않는다.
-- 기존 date의 NULL 허용은 별도 competition-report-nullable-analysis-date.sql 적용 여부를 확인한다.
