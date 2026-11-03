# 대결 취소와 종료 계좌 비활성화

작성일: 2026-11-02. 구현 브랜치: `feat/232-competition/cancel`.

## 취소 API 호출 흐름

```text
POST /api/v2/users/me/competitions/{competitionId}/cancel
 → 기존 SecurityConfig의 JWT·CSRF 검사
 → CompetitionController.cancelCompetition(jwt, competitionId)
 → CompetitionCancellationService.cancel(userId, competitionId)
   → TransactionTemplate.execute() [READ_COMMITTED]
     → CompetitionRepository.findLockedById(competitionId) → 소유권 확인·완료 대결 거절
     → CompetitionParticipantRepository.findAllByCompetitionIdOrderByIdAsc(competitionId)
     → 사용자·AI 참가자 구성 검사
     → 계좌 ID 오름차순으로 AccountRepository.findLockedById(id)
       → EntityManager.refresh(account) → Account.deactivate()
     → OrderRepository.findAllByAccountIdsAndStatus(ids, PENDING) → Order.cancel(now)
     → Competition.cancel() → CANCELLED
   → DB 커밋
   → 큐 기능이 구성돼 있으면 OrderRequestTransactions.cleanupInactiveAccount(id)
   → OrderSubscriptionService.refresh()
 → CompetitionCancelResponse(competitionId, "cancelled") → HTTP 200
```

취소 요청이 들어오면 competition.controller의 [CompetitionController.cancelCompetition()](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionController.java)가 호출된다. 인증된 JWT는 `jwt`, URL의 대결 ID는 `competitionId` 인자로 전달된다. 요청 본문은 없다. 컨트롤러는 JWT subject를 `Long.parseLong()`으로 변환한 사용자 ID와 대결 ID를 [CompetitionCancellationService.cancel()](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCancellationService.java)에 전달한다.

서비스는 대결을 잠그고 소유권을 검사한다. 참가자 둘 중 AI 계좌가 하나인지 확인하고, 계좌 ID 순서로 양측 계좌를 잠근다. 잠금을 기다리는 동안 완료된 체결이 있을 수 있으므로 `refresh()`로 최신 상태를 읽고 계좌를 비활성화한다. 대기 주문을 취소한 후 `Competition.cancel()`로 CANCELLED로 변경한다. 계좌·주문·대결 변경은 하나의 DB 트랜잭션이므로 중간 오류 시 함께 롤백된다.

현재 HTTP 컨트롤러는 외부 트랜잭션 없이 서비스를 호출한다. 이 경로에서는 TransactionTemplate 종료 후 DB 커밋이 끝나 예약 정리·구독 갱신을 실행한다. 향후 다른 트랜잭션 안에서 호출한다면 트랜잭션 참여로 인해 이 시점이 실제 커밋 이후인지 별도 검토해야 한다. 정리 실패는 로그로 남기고 이미 완료된 취소를 성공으로 반환한다. 한 계좌의 예약 정리가 실패해도 다른 계좌의 정리를 계속한다.

| 전달값 | 출처·역할 |
|---|---|
| userId | 인증된 JWT subject, 대결 소유권 확인 |
| competitionId | URL 경로, 대결 잠금·참가자 조회 |
| ids | 참가 계좌 ID 정렬 목록, 잠금·주문 취소·예약 정리 |
| now | CompetitionTradingHours.now().toInstant(), 주문 취소 시각 |

## 요청·응답·예외

access_token 쿠키와 기존 CSRF 쿠키·헤더를 사용한다. 요청 본문 없이 `POST /api/v2/users/me/competitions/1/cancel`을 호출한다.

```json
{"competition_id": 1, "state": "cancelled"}
```

| 조건 | 응답 |
|---|---|
| 본인 READY·IN_PROGRESS | 200, 취소 처리 |
| 본인 CANCELLED 재요청 | 200, 같은 응답·정리 재시도 |
| COMPLETED | 409 COMPETITION_ALREADY_COMPLETED |
| 타인 또는 없는 대결 | 404 COMPETITION_NOT_FOUND |
| 잘못된 참가자 구성 | 500 INVALID_COMPETITION_PARTICIPANTS |
| 미인증 | 기존 보안 설정의 401. CSRF가 먼저 실패하면 403일 수 있음 |
| CSRF 검사 실패 | 403 |

CompetitionException은 기존 CompetitionExceptionHandler.handle()에서 상태와 code/message 응답으로 변환된다. 취소는 승자·최종 정산·리포트 생성 요청을 만들지 않는다. 계좌·보유 종목·체결·일별 성적을 삭제하거나 강제 매도하지 않는다. 취소 시각 컬럼은 추가하지 않았다.

## 정상 종료와 계좌 비활성화

```text
CompetitionCompletionScheduler.completeExpiredCompetitions()
 → CompetitionCompletionService.completeCompetition(id)
   → 대결·계좌 잠금 → 최종 평가금·승자 확정 → 대기 주문 취소
   → Competition.complete(winner, now)
   → CompetitionDailyPerformanceService.recordFinalPerformances(competitionId)
   → 양측 Account.deactivate()
 → 함께 커밋
```

[CompetitionCompletionService](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionService.java)는 마지막 일별 성적 저장 뒤 참가 계좌를 비활성화한다. 기존 is_active 컬럼을 사용하므로 이번 작업의 DB 스키마 변경은 없다. 계좌 목록은 활성 계좌만 반환하므로 종료·취소 계좌가 숨겨진다. 과거 완료 계좌를 소급 변경하지 않는다. 현재 브랜치에는 SQS 리포트 요청 outbox 연동이 포함되지 않았다.

## 비활성 계좌의 기록 조회

```text
기록 조회 → AccountRepository.findByIdAndUserId(accountId, userId)
 → CompetitionHistoryAccess.check(userId, accountId, requireCompetition)
 → 기존 주문·체결·판단 근거 조회 및 응답
```

[AccountRepository](../src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java)의 findByIdAndUserId()는 활성 여부와 관계없이 소유권으로 계좌를 찾는다. CompetitionTradeHistoryService.list(), OrderHistoryService.list(), AiReportService.get()에서 사용한다. 비활성 일반 계좌도 본인이면 과거 기록과 판단 근거를 볼 수 있다. 대결 전용 매매 내역 URI는 일반 계좌를 계속 거절한다.

CompetitionHistoryAccess.check()는 대결 AI 기록을 COMPLETED에서만 공개한다. CANCELLED의 AI 매매 내역·판단 근거는 계속 비공개이며 사용자 자신의 기록은 조회 가능하다. 대결 상세·결과는 참가자와 저장 성적 기준으로 조회해 비활성화 후에도 보존된다. 계좌 목록·신규 거래·계좌 수정·보유 종목 조회의 활성 조건은 변경하지 않았다.

## 큐 주문과 정리 실패

취소 API가 SQS 메시지를 직접 삭제하지는 않는다. OrderRequestService.consume()는 rejectIfInactive()로 계좌를 먼저 검사한다. 소비 도중 취소되면 OrderRequestTransactions.process()의 계좌 잠금·검증 및 예외 처리에서 ACCOUNT_INACTIVE로 거절한다. 같은 메시지를 다시 소비해도 주문이 생기지 않는다. cleanupInactiveAccount()는 예약의 거절 결과를 DB에 기록한 뒤 예약을 제거한다. 실패한 예약은 기존 reconcile()이 정리한다.

취소가 계좌 잠금을 보유한 동안 체결이 도착하면 기다렸다가 커밋된 비활성 상태를 확인해 체결을 중단한다. 취소 전에 커밋된 거래는 보존한다.

| 로그 이벤트 | 의미 |
|---|---|
| competition_cancellation_cleanup_failed | 커밋 후 계좌별 예약 정리 실패. 대결·계좌 ID, 오류 유형과 스택 기록 |
| competition_cancellation_subscription_failed | 커밋 후 구독 갱신 실패. 대결 ID, 오류 유형과 스택 기록 |

## 검증

- CompetitionCancellationStateTests: 상태 전환·반복 취소·완료 대결 거절.
- CompetitionCancellationServiceTests: HTTP 인증·CSRF·소유권·성공 응답, 대기·진행 취소, DB 오류 롤백, 후속 정리 실패, 취소·정산 두 처리 순서, 대기 체결 차단, 정상 종료 계좌 숨김과 결과 조회.
- CompetitionTradeHistoryTests: 비활성 일반·대결 계좌 기록 조회, 타인 거절, 취소 AI 비공개.
- OrderRequestTransactionsTests: 취소 전후 메시지 소비와 중복 전달 거절, 예약 정리.

동시 처리 테스트는 테스트 DB와 스레드를 사용한다. 큐 테스트는 실제 AWS SQS 대신 소비 메서드를 호출하고 메모리 예약 저장소를 사용한다. 운영 DB·Redis·SQS의 장애나 다중 서버 검증을 대체하지 않는다.

2026-11-02 최종 검증: `gradlew.bat test` 성공. 총 583개, 실패 0·오류 0·건너뜀 18개. `git diff --check` 통과. 건너뛴 테스트와 실제 운영 연동은 이 결과로 검증했다고 보지 않는다. 코드·문서는 현재 작업 트리에 있으며 커밋·푸시·배포는 이번 작업에 포함하지 않았다.
