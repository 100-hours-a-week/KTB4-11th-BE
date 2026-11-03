# 대결 리포트 요청 배포 시 적용할 변경 목록

이 문서는 작업 마무리 시 다시 확인할 운영 적용 목록이다. 이번 개발에서는 운영 DB·AWS·배포 설정을 변경하지 않았다.

## 1. 운영 DB 변경 (앱 배포 전에 적용)

| 대상 | 변경 | SQL |
|---|---|---|
| ai_order_reports | reason_id VARCHAR(36) NULL 추가 | [ai_report_reason_id.sql](../db/ai_report_reason_id.sql) |
| competition_report_outbox | 대결별 전송 대기·성공 기록 테이블, 유일 제약·FK·조회 인덱스 생성 | [competition-report-outbox.sql](../db/competition-report-outbox.sql) |
| competitions | daily_performance_complete BOOLEAN NOT NULL DEFAULT FALSE 및 후보 조회 인덱스 추가 | [competition-daily-performance-complete.sql](../db/competition-daily-performance-complete.sql) |

실제 대상 DB에서 먼저 기존 테이블·컬럼·인덱스 존재 여부를 확인한다. SQL은 중복 실행용이 아니므로 존재한다면 정의를 대조하고 필요한 변경만 반영한다. 과거 주문의 reason_id는 NULL로 유지하며 임의 생성하지 않는다. 기존 report_id 및 주문 테이블 속성은 변경하지 않는다.

새 엔티티 매핑은 reason_id 컬럼을 조회·저장하므로 ALTER가 먼저다. 회원 탈퇴 삭제 코드도 Outbox 테이블을 참조하므로 해당 테이블 역시 먼저 필요하다. 기존 앱은 추가 컬럼·테이블을 사용하지 않으므로 애플리케이션을 롤백해도 기본적으로 DB 추가분은 남긴다. 기록을 삭제하는 DROP은 자동 롤백 절차가 아니다.

상세 확인 쿼리와 적용 순서는 [reason_id 운영 DB 변경](ai-order-reason-id-db-changes.md)을 참고한다. 실제 DB 크기에 따른 ALTER 잠금·소요 시간은 운영 환경에서 별도 판단한다.

## 2. 설정·권한

- REPORT_QUEUE_URL: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-report-request
- AWS_REGION: ap-northeast-2
- REPORT_QUEUE_ENABLED: 기본 false. true로 설정하면 Report Queue 클라이언트·서비스·스케줄러를 생성한다.
- REPORT_DISPATCH_ENABLED: 기본 true. 첫 기동 때 전송을 유예하려면 false로 지정한다.
- REPORT_DISPATCH_INTERVAL_MS: 기본 60000, REPORT_DISPATCH_BATCH_SIZE: 기본 20.
- COMPETITION_COMPLETION_CLOSE_CRON: 기본 `0 30,35 15 * * MON-FRI`. 한국 시간 평일 15:30·15:35 정산을 시도하며 기존 1시간 fixedDelay 복구도 유지한다.
- EC2 IAM Role에 대상 Report Queue의 sqs:SendMessage 권한이 필요하다. Access/Secret Key는 저장하지 않는다.
- AI 소비자는 SQS 메시지 속성 eventId를 읽어 동일 요청 재전송을 중복 처리해야 한다. 소비 측 지원은 아직 확인하지 않았다.

두 활성화 옵션이 true면 이미 저장된 PENDING도 첫 실행부터 처리한다. 과거 완료 대결에 Outbox 기록을 소급 생성하지는 않는다. 기존 PENDING에 체결 주문의 UUID가 없으면 빈 배열 또는 일부 ID로 보내고 누락 건수를 경고 로그에 남긴다.

## 3. 적용 후 확인

- 새 AI 지정가·시장가 주문의 원본 reason_id가 리포트 행에 저장되는지 확인한다.
- 대결 종료 시 PENDING 생성, 전송 후 SENT 전환을 확인한다. SENT는 SQS 전송 성공이며 AI 보고서 생성 완료는 아니다.
- competition_report_published의 requestId·messageId와 competition_report_outbox_sent의 requestId를 연결해 확인한다.
- 실패 시 competition_report_dispatch_failed의 stage와 competition_report_publish_failed를 확인한다.
- AI 서버가 큐를 소비하고 결과 저장 API를 호출하는지 확인한다. 실제 연동 검증은 이번 로컬 테스트 범위에 포함되지 않았다.

전체 호출 흐름 및 잠금·재시도 한계는 [Outbox 문서](competition-report-outbox.md)를 참고한다. 정산 전용 스레드·후보 조회 축소·장 마감 예약 및 잠금 비용 검토 결과는 [스케줄러 점검 문서](competition-scheduler-review.md)에 반영했다.

일별 기록 복구 완료 표시도 이번 코드에서 참조하므로 competitions 컬럼·인덱스를 먼저 적용해야 한다. 기존 행은 false로 시작해 최초 한 번 복구 검사를 거친다. 과거 완료 대결이 많다면 첫 복구 실행에 시간이 걸릴 수 있다. 운영 DB에서 성과 기록을 별도 수정·삭제했다면 해당 competition_id의 daily_performance_complete를 false로 되돌린 뒤 복구한다.
