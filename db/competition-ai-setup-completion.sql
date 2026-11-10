-- 수동 적용용. BE 배포 전에 적용하며 애플리케이션에서 자동 실행하지 않는다.
-- 기존 대결의 NULL은 준비 미확인이다. 과거 SENT를 준비 완료로 일괄 전환하지 않는다.
ALTER TABLE competitions ADD COLUMN ai_setup_completed_at DATETIME(6) NULL;
