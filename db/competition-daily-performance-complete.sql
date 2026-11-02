-- 운영 DB에 자동 적용하지 않는다. 새 엔티티/후보 조회 배포 전에 한 번 적용한다.
-- 기존 완료 대결도 false로 시작해 전체 일별 기록을 검증·복구한 후 후보에서 제외한다.
ALTER TABLE competitions
    ADD COLUMN daily_performance_complete BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX ix_competitions_daily_pending (state, daily_performance_complete, start_date);
