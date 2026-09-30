-- ai_delegated만 있는 DB에 적용한다. 기존 값을 유지하면서 컬럼 이름을 변경한다.
-- 두 컬럼이 모두 있는 로컬 DB에는 적용하지 않는다.
ALTER TABLE accounts RENAME COLUMN ai_delegated TO is_ai_managed;
