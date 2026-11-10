-- 이력 보존: 과거 콜백 방식용 SQL. 현재 SENT 시작 정책에는 실행하지 않는다. 이미 적용한 컬럼은 유지한다.
-- 아래는 과거 콜백 방식의 수동 적용 구문이다. 현재 배포에는 적용하지 않으며 자동 실행하지 않는다.
-- 기존 대결의 NULL은 준비 미확인이다. 과거 SENT를 준비 완료로 일괄 전환하지 않는다.
ALTER TABLE competitions ADD COLUMN ai_setup_completed_at DATETIME(6) NULL;
