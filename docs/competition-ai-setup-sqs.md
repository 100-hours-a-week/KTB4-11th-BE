# AI 전략 설정 SQS 전송 (#260)

AI 대결 생성 시 저장한 전체 전략을 메시지 하나로 AI 서버에 전달한다. 생성 응답은 기존 ready를 유지하며 SQS 응답을 기다리지 않는다.

## 처리 순서

1. 기존 생성 트랜잭션에서 계좌·참가자·전략과 전송 예약을 함께 저장한다. 실패하면 모두 되돌린다.
2. 아웃박스에 UUID event_id와 실제 저장 전략으로 만든 JSON 본문을 고정한다. 대결당 예약은 한 건이다.
3. 전용 스케줄러가 시작 시점의 미전송 후보를 ID 순으로 조회한다. 실패 행 때문에 뒤쪽 예약이 막히지 않는다.
4. 대결→아웃박스 순으로 잠근다. CANCELLED 대결은 예약도 CANCELLED로 변경한다. READY AI 대결만 전송한다.
5. SQS 성공 응답의 messageId를 확인한 뒤 SENT로 저장한다. 실패하면 PENDING으로 남아 다음 주기에 같은 본문으로 재시도한다.

SENT는 AI 설정 완료가 아니다. 전송 성공 후 DB 저장 실패 등으로 중복 전달될 수 있으므로 AI 서버는 event_id로 중복을 막아야 한다. 이미 발송된 메시지는 대결 취소로 회수되지 않는다. 실제 대결 시작은 계속 차단한다.

## 계약

본문: event_id, event_type=competition_ai_setup, competition_id, agents.
각 agent: participant_id, account_id, risk_level, preferred_themes, excluded_themes, additional_request.
AI 2~4명을 설정 순서로 포함한다. risk_level은 1=안정형, 5=성장형이다. agent_id는 보내지 않는다.

## 설정과 운영 적용

application.yaml의 competition.ai-setup을 사용한다.

- AI_SETUP_QUEUE_ENABLED: 기본 false. false여도 생성 예약은 저장한다.
- AI_SETUP_QUEUE_URL: 개발/운영별 아래 URL 주입
- AWS_REGION: 기본 ap-northeast-2
- AI_SETUP_DISPATCH_ENABLED: 기본 true. 전송 일시 중지용
- AI_SETUP_DISPATCH_INTERVAL_MS: 기본 60000
- AI_SETUP_DISPATCH_BATCH_SIZE: 기본 20 (1~100)

개발: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-ai-request
운영: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-prod-ai-request

기존 AWS 기본 인증 체인을 사용한다. BE에는 SendMessage 권한이 필요하다. AI의 수신·삭제 권한과 DLQ 설정은 인프라/AI 담당자가 확인한다. BE 발송 실패는 아웃박스에서, AI 수신 후 반복 실패는 SQS/DLQ에서 관리한다.

배포 전 db/competition-ai-setup-outbox.sql을 수동 적용해야 한다. 기존 리포트 테이블은 바꾸지 않는다. 이 작업에서 원격 DB에 SQL을 적용하지 않는다. 기존 대결은 소급 전송하지 않는다.

개발 연동은 AI 담당자 수신 준비와 권한 확인 후 전송을 활성화하고 생성→예약→SQS 수신을 확인한다. 운영 전송을 로컬에서 실행하지 않는다. 전송을 끄면 예약이 누적되므로 재활성화 전에 유효 대결을 확인한다.

## 범위

BE만 수정한다. FE/AI 코드는 수정하지 않는다. 설정 완료 통지, 실제 시작 활성화, 다수 AI 리포트는 별도 작업이다. 실제 AWS 연동과 운영 DB 적용은 아직 수행하지 않았다.

## 검증 기록 (2026.11.07)

- 새 메시지·SQS 발송·재시도·취소·아웃박스 저장과 통합 생성·기존 HTTP 시나리오: 22개 통과, 실패·오류·건너뜀 0개.
- SpotBugs 및 git diff --check 통과.
- 검증 중 겹친 Gradle 실행에서 EOF 오류가 발생해 종료 후 cleanTest로 정리하고 단일 실행으로 위 결과를 확인했다.
- 실제 SQS와 원격 MySQL 적용은 실행하지 않았다.

- 최종 전체 테스트: 744개, 실패 0, 오류 0, 건너뜀 24. 단일 Gradle 실행 BUILD SUCCESSFUL 확인.
