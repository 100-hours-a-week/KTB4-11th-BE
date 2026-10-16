-- 기존 competition.sql을 이미 적용한 DB용 변경이다. 신규 DB는 최신 competition.sql만 적용한다.
-- 두 스크립트를 같은 DB에 중복 적용하지 않는다. 실제 DB에는 자동 실행하지 않는다.
ALTER TABLE competitions
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD COLUMN winner ENUM('USER', 'AI', 'DRAW') NULL;

ALTER TABLE competition_participants
    ADD COLUMN final_asset_value BIGINT NULL,
    ADD COLUMN final_return_percent DECIMAL(19,4) NULL;
