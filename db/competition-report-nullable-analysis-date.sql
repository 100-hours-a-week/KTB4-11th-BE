-- 기존 테이블의 뉴스 날짜에 NULL을 허용한다. 운영 DB에는 자동 실행되지 않는다.
-- 적용 전 competition_market_analyses 테이블과 현재 컬럼 정의를 확인한다.
ALTER TABLE competition_market_analyses MODIFY COLUMN analysis_date DATE NULL;
