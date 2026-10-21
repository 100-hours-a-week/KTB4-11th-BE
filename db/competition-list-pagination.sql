-- 기존 competitions 테이블에 적용할 목록 조회용 인덱스다.
-- 운영 DB에는 자동 적용하지 않는다. 적용 전 SHOW INDEX FROM competitions로 중복 여부를 확인한다.
-- 사용자별 최신순 범위를 지원한다. state 필터의 추가 인덱스는 실제 실행 계획을 보고 판단한다.
CREATE INDEX ix_competitions_user_created_id
    ON competitions (user_id, created_at, competition_id);
