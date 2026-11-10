# AI 간 대결 준비 완료와 자동 시작

AI 간 대결은 생성 후 READY로 대기한다. AI 서버가 모든 참가자의 전략을 영구 저장한 뒤 준비 완료를 통지하면, BE가 이를 기록하고 다음 장중 시작 스케줄러에서 IN_PROGRESS로 바꾼다. 메시지를 SQS에 보낸 것만으로 시작하지 않는다.

## API 계약

`POST /api/v1/competitions/{competitionId}/ai-setup/complete`

```json
{"event_id":"a5af5f21-9bde-4d03-895d-dfe7d8340211"}
```

- event_id는 생성 당시 SQS 메시지의 UUID를 그대로 사용한다. 새로 발급하지 않는다.
- 인증은 기존 리포트 저장과 동일하다. `actor=AI`, `type=access`, subject=대결 소유 사용자 ID인 서명된 JWT를 `access_token` 쿠키로 보낸다. 전역 `ai-server` 토큰과 일반 사용자 토큰은 허용하지 않는다. Authorization 헤더는 인증 경로가 아니다.
- CSRF가 활성화돼 있으면 기존 `/api/v1/auth/csrf` 응답의 XSRF 쿠키와 `X-XSRF-TOKEN` 헤더도 보낸다.
- 정상·동일 이벤트 중복 통지: 204, 응답 본문 없음. 최초 완료 시각은 보존한다. 시작·종료 후 동일 이벤트 재시도도 204다.
- 요청 누락·잘못된 UUID: 400. 인증 실패: 401, 권한·CSRF 실패: 403.
- 대결 미존재·다른 소유자: 404 COMPETITION_NOT_FOUND.
- 다른 이벤트: 404 AI_SETUP_REQUEST_NOT_FOUND.
- 사용자 vs AI: 409 COMPETITION_TYPE_NOT_SUPPORTED.
- 취소: 409 COMPETITION_CANCELLED. 최초 완료 통지 시 실제 거래 종료 시각 경과: 409 COMPETITION_SETUP_EXPIRED.

이 API는 모든 참가자가 준비됐다는 AI 서버의 통지를 신뢰한다. AI 서버는 일부 참가자만 설정한 상태에서 호출하면 안 된다. 참가자별 준비 통지를 합산하는 API는 아니다.

## 코드 역할과 처리 순서

1. CompetitionService는 참가자·계좌·전략과 Outbox를 같은 트랜잭션에 저장한다.
2. CompetitionAiSetupDispatchService는 기존 방식으로 SQS에 전달한다. SENT는 전달 완료다.
3. CompetitionAiSetupCallbackController가 완료 요청을 받고 CompletionService가 소유권·유형·event_id·취소·기간을 검사한다.
4. competitions.ai_setup_completed_at에 BE 수신 시각을 기록한다. 콜백은 대결을 직접 시작하지 않는다.
5. CompetitionRepository.findWaitingToStart는 기존 사용자 대결과 준비 완료된 AI 대결을 조회한다. CompetitionStartScheduler가 평일 09:00 이상 15:30 미만 및 실제 거래 종료 전인지 확인해 시작한다.
6. 시작 후 기존 AI snapshot의 운용 대상 조회에 참가 계좌가 포함된다. 실제 첫 투자 판단 실행 시각은 AI 서버의 조회·운용 주기에 달려 있다.

완료·발송·시작·취소는 대결 잠금을 먼저 사용한다. 완료 통지는 해당 이벤트의 Outbox를 SENT로도 표시한다. AI 소비가 확인되므로, 발송 후 DB 커밋 실패로 PENDING만 남은 경우 재전송을 멈출 수 있다. 발송 처리 역시 같은 대결 잠금을 거치므로 동시 수정과 순서가 맞는다.

## 시간·실패 정책

- 전날 18시 준비 완료 → 다음 평일 09시 이후 스케줄러에서 시작.
- 당일 10시 준비 완료 → 그 이후 스케줄러에서 시작. 기본 fixed delay는 10초이며 부하·설정에 따라 지연될 수 있다.
- 설정 실패·완료 통지 지연 → READY 유지. SQS 전송 실패는 기존 Outbox 재시도, HTTP 응답 유실·5xx는 AI가 같은 event_id로 재시도한다.
- 종료일은 연장하지 않는다. 시작 전 실제 종료 시각을 넘기면 READY로 남고 기존 취소 API를 사용할 수 있다. 자동 취소나 새 실패 상태는 추가하지 않았다.
- 주말 종료는 직전 금요일 15:30이 실제 종료다. 공휴일은 기존 시간 정책과 동일하게 미반영이다.

## 적용 순서와 외부 연동

1. `db/competition-ai-setup-completion.sql`을 대상 DB에 수동 적용한다. 기존 컬럼 유무를 확인하고 한 번만 실행한다. 자동 실행 SQL이 아니다.
2. BE를 배포한다. 기존 READY/SENT 대결은 준비 완료로 소급 변환하지 않는다.
3. AI 서버에서 전체 참가자 설정 저장, event_id 중복 방지, 완료 콜백 및 재시도를 구현·검증한다.
4. 개발 환경에서 생성 → 설정 전달 → 전체 준비 완료 → 시작 → AI 계좌 조회 → 주문·성과·정산·리포트 흐름을 확인한 뒤 운영에 적용한다.

이 작업은 BE 구현과 로컬 검증이다. AI 저장소 수정, 실제 AWS 소비·발행 검증, 개발·운영 DB SQL 적용 및 배포는 포함하지 않는다. AI 서버의 완료 호출이 연결되기 전에는 AI 대결이 계속 READY인 것이 정상이다.
