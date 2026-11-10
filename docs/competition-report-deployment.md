# 대결 리포트 요청 배포 시 적용할 변경 목록

이 문서는 작업 마무리 시 다시 확인할 운영 적용 목록이다. 이번 개발에서는 운영 DB·AWS·배포 설정을 변경하지 않았다.

## 전달·적용 상태 (2026-11-09 사용자 확인)

- 사용자가 competition-report-outbox-participant.sql과 competition-reports-participant.sql을 인프라 담당자에게 전달했다. SQL 전달은 완료됐으며 운영 DB 적용 완료는 아직 확인되지 않았다. 적용 사실이 확인되면 이 상태를 갱신한다.
- 대상은 db/competition-report-outbox-participant.sql과 db/competition-reports-participant.sql이다. 새 코드 배포 후 DB 관련 오류가 발생하면 실제 대상 DB에 해당 변경이 반영됐는지 우선 확인한다.
- Unknown column의 participant_id/report_ready, 스키마 검증 실패, 기존 uk_report_outbox_competition 중복 오류가 단서다. 코드 버전·실제 컬럼/제약과 로그의 SQL 원인을 대조하며 모든 500 오류를 스키마 문제로 단정하지 않는다.
- PR #263의 기존 CI 실패는 운영 DB와 무관한 이전 테스트 계약 문제였고 e0c4e80에서 수정했다. [수정 후 CI](https://github.com/100-hours-a-week/KTB4-11th-BE/actions/runs/42485462204)는 통과했다. CI는 별도 H2 DB를 자동 생성하므로 CI 통과가 운영 SQL 반영을 의미하지 않는다.

## 1. 운영 DB 변경 (앱 배포 전에 적용)

| 대상 | 변경 | SQL |
|---|---|---|
| ai_order_reports | reason_id VARCHAR(36) NULL 추가 | [ai_report_reason_id.sql](../db/ai_report_reason_id.sql) |
| competition_report_outbox (신규 설치) | AI 참가자별 전송 기록 테이블, 참가자 FK·복합 유일 제약·조회 인덱스 생성 | [competition-report-outbox.sql](../db/competition-report-outbox.sql) |
| competition_report_outbox (기존 테이블) | participant_id 추가·기존 단일 AI 연결·대결 단독 유일 제약을 대결/참가자 복합 제약으로 교체 | [competition-report-outbox-participant.sql](../db/competition-report-outbox-participant.sql) |
| competitions | daily_performance_complete BOOLEAN NOT NULL DEFAULT FALSE 및 후보 조회 인덱스 추가 | [competition-daily-performance-complete.sql](../db/competition-daily-performance-complete.sql) |
| competitions | report_ready BOOLEAN NOT NULL DEFAULT FALSE 추가 및 기존 리포트 완료 표시 보정 | [competition-report-ready.sql](../db/competition-report-ready.sql) |
| AI 참가자별 리포트 (신규 설치) | 참가자 report_ready, 본문 participant_id FK·인덱스 생성 | [competition-reports.sql](../db/competition-reports.sql) |
| AI 참가자별 리포트 (기존 테이블) | 본문 참가자 연결·완료 상태 이관·NOT NULL 및 FK 추가 | [competition-reports-participant.sql](../db/competition-reports-participant.sql) |

실제 대상 DB에서 먼저 기존 테이블·컬럼·인덱스 존재 여부를 확인한다. SQL은 중복 실행용이 아니므로 존재한다면 정의를 대조하고 필요한 변경만 반영한다. 과거 주문의 reason_id는 NULL로 유지하며 임의 생성하지 않는다. 기존 report_id 및 주문 테이블 속성은 변경하지 않는다.

### AI 참가자별 Outbox 이관 (#262)

신규 설치 SQL과 기존 테이블 이관 SQL 중 하나만 선택한다. 기존 테이블은 모든 BE 인스턴스의 관련 쓰기(종료 정산·전송·회원 탈퇴)를 중지하고 백업한 뒤 이관한다. REPORT_DISPATCH_ENABLED=false만으로 종료 서비스의 Outbox 저장까지 중지되지는 않는다. MySQL DDL은 암묵적으로 커밋되므로 단계별 확인이 필요하다.

먼저 nullable participant_id를 추가하고, AI가 정확히 한 명인 대결의 기존 행에 그 참가자를 연결한다. 미연결·소속 불일치 조회 결과가 0건인지 확인한 뒤 NOT NULL·참가자 FK·(competition_id, participant_id) UNIQUE를 적용하고 기존 competition_id 단독 UNIQUE를 제거한다. 모호한 행은 수동 확인하며 request_id·PENDING/SENT 상태를 보존한다. 회원 탈퇴 시 Outbox를 참가자보다 먼저 삭제하도록 코드도 변경했다.

이관 후에는 participant_id를 쓰지 않는 구버전 앱이 동작하지 않으므로 구·신버전 동시 쓰기를 허용하지 않는다. 앱 롤백은 추가 컬럼을 남기는 것만으로 충분하지 않다. 다중 AI 행이 생성된 뒤에는 기존 대결 단독 UNIQUE도 그대로 복원할 수 없으므로 별도 롤백 계획이 필요하다. 기존 완료 AI 대결에 요청을 소급 생성하지는 않는다.

SQS 본문은 competition_id, participant_id, portfolio_reason_ids 세 필드이며 user_id를 제거했다. AI 서버와 이 계약을 맞춘다. 참가자별 저장·조회도 확장했으며 다음 절차까지 적용한 뒤 다중 AI 리포트 콜백을 연동한다. AI 대결 시작은 별도의 [전송 성공·시작 계약](competition-ai-start.md)을 따른다.

### AI 참가자별 리포트 저장·조회 이관

기존 competitions.report_ready 컬럼은 보존하지만 새 저장·조회는 참가자의 report_ready를 사용한다. 기존 컬럼이 아직 없다면 기존 ALTER를 먼저 적용한다. 내용이 없고 과거 수신 완료 표시도 없던 보고서는 완료 여부를 추측하지 않는다. 조회는 reports 배열 내부의 report_state로 pending/completed를 반환하며 대결 완료 상태와 별개다.

- 신규 설치용 competition-reports.sql과 기존 테이블 이관용 competition-reports-participant.sql 중 하나를 선택한다. 컬럼이 이미 있다면 재실행하지 말고 정의를 확인한다.
- 모든 인스턴스의 관련 쓰기를 중지하고 AI 콜백 재시도를 조율한다. 참가자 완료 컬럼과 nullable 본문 참가자 컬럼 추가 → 단일 AI에 본문 연결 → 검증 → 준비 상태 이관 → NOT NULL·FK·인덱스 순서로 적용한다.
- 기존 본문 ID·내용·날짜를 보존한다. 정상 수신 표시가 있는 빈 보고서도 단일 AI이면 completed로 보존한다. 대상이 불명확한 행을 임의 배분하지 않으며 검증 결과가 있으면 중단한다.
- POST URI는 유지하지만 본문에 필수 양수 participant_id를 추가한다. AI 서버는 SQS에서 받은 참가자 ID를 콜백에 포함해야 한다. 기존 JWT subject·AI actor·CSRF 계약은 유지한다.
- GET URI도 유지하지만 최상위 보고서 필드를 reports 배열로 옮긴다. 사용자 vs AI는 1개, AI 간 대결은 AI 수만큼이며 미수신 AI는 pending으로 포함한다. FE 수정과 배포 순서를 조율한다.
- 구버전은 participant_id 없이 본문을 저장하고 대결 전체 내용을 교체하므로 혼용하지 않는다. 새 코드가 참가자 report_ready만 갱신하므로 구버전으로 단순 롤백하면 상태 누락·본문 덮어쓰기가 발생할 수 있다. 별도 데이터/계약 롤백 계획이 필요하다.
- 뉴스 날짜는 null이다. 기존 analysis_date가 NOT NULL이면 competition-report-nullable-analysis-date.sql 적용 여부도 확인한다.
- 회원 탈퇴 시 본문과 Outbox를 참가자보다 먼저 삭제하는 새 코드를 함께 배포한다. 이번 작업에서 운영 DB 적용·AI 서버 수정·FE 변경·실제 AWS 연동은 수행하지 않았다.

실행 흐름과 요청·응답 예시는 [AI 참가자별 저장·조회](competition-participant-reports.md)를 참고한다.

새 엔티티 매핑은 reason_id와 참가자별 보고서 컬럼을 조회·저장하므로 DB 변경이 먼저다. 회원 탈퇴 코드도 Outbox 테이블을 참조하므로 해당 테이블 역시 먼저 필요하다. 기록을 삭제하는 DROP은 자동 롤백 절차가 아니다. 참가자별 Outbox·리포트의 구버전 호환 제약은 위 절차를 따른다.

상세 확인 쿼리와 적용 순서는 [reason_id 운영 DB 변경](ai-order-reason-id-db-changes.md)을 참고한다. 실제 DB 크기에 따른 ALTER 잠금·소요 시간은 운영 환경에서 별도 판단한다.

## 2. 설정·권한

- REPORT_QUEUE_URL: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-report-request
- AWS_REGION: ap-northeast-2
- REPORT_QUEUE_ENABLED: 현재 application.yaml 기본 true. true이면 Report Queue 클라이언트·서비스·스케줄러를 생성한다. 실제 배포 환경변수는 별도 확인한다.
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
