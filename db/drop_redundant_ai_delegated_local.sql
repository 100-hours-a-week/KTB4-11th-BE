-- ai_delegated와 is_ai_managed가 모두 있고 accounts가 비어 있는 로컬 DB에만 적용한다.
-- 2026-09-29 확인 당시 두 컬럼은 bit(1) NOT NULL, 계좌는 0행이었다.
ALTER TABLE accounts DROP COLUMN ai_delegated;
