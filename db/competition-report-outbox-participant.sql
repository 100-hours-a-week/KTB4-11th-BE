-- 기존 MySQL 테이블 이관용. 신규 설치 DDL과 함께 실행하지 않는다. 자동 실행되지 않는다.
-- 모든 BE 인스턴스의 종료 정산·리포트 전송·회원 탈퇴 등 관련 쓰기를 중지하고 백업한 뒤 단계별 적용한다.
-- MySQL DDL은 암묵적으로 커밋되므로 전체 스크립트의 트랜잭션 롤백을 기대하지 않는다.
-- SHOW CREATE TABLE competition_report_outbox로 실제 컬럼·인덱스·FK 이름을 먼저 확인한다.

-- 1. 구버전 Outbox에 대상 참가자를 연결할 nullable 컬럼을 추가한다 (한 번만 실행).
ALTER TABLE competition_report_outbox ADD COLUMN participant_id BIGINT NULL;

-- 2. AI가 정확히 한 명이고 계좌 소유자가 대결 소유자와 같은 기존 기록만 이관한다.
-- request_id, status, created_at은 변경하지 않는다. SENT를 PENDING으로 되돌리지 않는다.
UPDATE competition_report_outbox o
JOIN competitions c ON c.competition_id = o.competition_id
JOIN (
    SELECT p.competition_id, MIN(p.participant_id) AS participant_id, MIN(a.user_id) AS user_id
    FROM competition_participants p
    JOIN accounts a ON a.account_id = p.account_id
    WHERE a.is_ai_managed = TRUE
    GROUP BY p.competition_id
    HAVING COUNT(*) = 1
) ai ON ai.competition_id = o.competition_id AND ai.user_id = c.user_id
SET o.participant_id = ai.participant_id
WHERE o.participant_id IS NULL;

-- 3. 반드시 결과 0건을 확인한다. 결과가 있으면 여기서 중단하고 원본 기록으로 대상 참가자를 확인한다.
-- 여러 AI 중 임의의 한 명을 골라 채우거나 기존 요청을 삭제하지 않는다.
SELECT o.outbox_id, o.competition_id, o.request_id, o.status
FROM competition_report_outbox o
LEFT JOIN competition_participants p ON p.participant_id = o.participant_id
LEFT JOIN accounts a ON a.account_id = p.account_id
LEFT JOIN competitions c ON c.competition_id = o.competition_id
WHERE o.participant_id IS NULL OR p.participant_id IS NULL OR p.competition_id <> o.competition_id
   OR a.is_ai_managed = FALSE OR a.user_id <> c.user_id;

-- 4. 0건 확인 후 실행. 구버전 앱은 participant_id를 저장하지 않으므로 이후 재시작하지 않는다.
ALTER TABLE competition_report_outbox
    MODIFY COLUMN participant_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_report_outbox_competition_participant UNIQUE (competition_id, participant_id),
    DROP INDEX uk_report_outbox_competition,
    ADD CONSTRAINT fk_report_outbox_participant FOREIGN KEY (participant_id)
        REFERENCES competition_participants(participant_id);

-- 5. SHOW CREATE TABLE 결과와 기존 request_id/status 보존을 확인한 후 신버전으로 재개한다.
-- 과거 완료된 AI 간 대결의 누락 Outbox를 소급 생성하는 기능은 이 스크립트에 포함하지 않는다.
