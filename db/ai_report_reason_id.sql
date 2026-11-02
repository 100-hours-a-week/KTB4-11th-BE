-- 운영 DB 변경 준비용. 자동 실행되지 않는다.
-- AiOrderReport.reasonId 매핑 및 저장 로직 배포 전에 대상 DB에서 한 번 적용해야 한다.
-- 기존 행은 NULL로 유지한다. BE의 report_id와 AI의 reason_id는 서로 다른 식별자다.
ALTER TABLE ai_order_reports
    ADD COLUMN reason_id VARCHAR(36) NULL;
