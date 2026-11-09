-- 운영 DB에 자동 실행하지 않는다. 새 엔티티 매핑 배포 전에 한 번 적용한다.
ALTER TABLE competitions ADD COLUMN report_ready BOOLEAN NOT NULL DEFAULT FALSE;

-- 기존 리포트 내용이 하나라도 있으면 이미 수신된 것으로 보존한다.
-- 앱 교체 중 구버전이 저장한 리포트가 누락되지 않게 아래 UPDATE는 배포 완료 후에도 재실행할 수 있다.
UPDATE competitions c
SET c.report_ready = TRUE
WHERE c.report_ready = FALSE
  AND (EXISTS (SELECT 1 FROM competition_market_analyses a WHERE a.competition_id = c.competition_id)
       OR EXISTS (SELECT 1 FROM competition_judgment_summaries s WHERE s.competition_id = c.competition_id));
