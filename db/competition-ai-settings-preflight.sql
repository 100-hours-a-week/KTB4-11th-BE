-- 읽기 전용 사전 점검. 반드시 의도한 DB를 선택한 세션에서 실행한다.
SELECT DATABASE() AS selected_database, VERSION() AS mysql_version, @@sql_mode AS sql_mode;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'competitions' AND COLUMN_NAME IN ('competition_id', 'competition_type', 'ai_settings_complete'))
    OR (TABLE_NAME = 'competition_participants' AND COLUMN_NAME IN ('participant_id', 'competition_id')))
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 기존 유형이 USER_VS_AI/AI_VS_AI 외에 있다면 정적 ENUM 변경을 실행하지 않는다.
SELECT competition_type, COUNT(*) AS row_count FROM competitions GROUP BY competition_type;

-- 최초 적용 전에는 아래 테이블이 없어야 한다. 있으면 재실행하지 말고 적용 이력을 확인한다.
SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('competition_agents', 'competition_agent_preferred_themes', 'competition_agent_excluded_themes');

-- 신규 FK의 participant_id는 운영 부모 키의 타입/부호와 정확히 일치해야 한다.
SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('competition_participants', 'competition_agents',
                     'competition_agent_preferred_themes', 'competition_agent_excluded_themes')
ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
