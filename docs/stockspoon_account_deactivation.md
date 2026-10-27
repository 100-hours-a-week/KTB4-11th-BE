# 계좌 삭제 요청 시 비활성화 흐름

## 1. API와 호출 흐름

```http
DELETE /api/v1/users/me/accounts/{accountId}
Cookie: access_token={StockSpoon Access JWT}; XSRF-TOKEN={CSRF cookie}
X-XSRF-TOKEN: {GET /api/v1/auth/csrf 응답의 token}
```

본문 없이 요청한다. 성공은 `200 OK`와 `{"message":"delete_success"}`다.

```text
SecurityConfig의 인증·CSRF 검사
→ account.controller.AccountController.delete(jwt, accountId)
  → JWT subject를 long userId로 변환
  → account.service.AccountDeletionService.delete(userId, accountId)
    → TransactionTemplate.executeWithoutResult(...) [READ_COMMITTED]
      → AccountRepository.findLockedById(accountId)
      → Account.belongsTo(userId): 없음·타인 소유이면 404
      → AccountRepository.findCompetitionByAccountId(accountId): 연결이 있으면 409
      → OrderRepository.findAllByAccountIdAndStatus(accountId, PENDING)
      → 각 Order.cancel(now)
      → Account.deactivate()
    → DB 커밋
    → 큐가 구성되어 있으면 OrderRequestTransactions.cleanupInactiveAccount(accountId)
      → 미처리 예약의 거절 결과 DB 커밋
      → OrderReservationStore.removeAll(accountId)
    → OrderSubscriptionService.refresh()
→ Controller에서 message=delete_success 구성
→ 200 OK
```

코드: [AccountController](../src/main/java/com/stock_spoon/river_be/account/controller/AccountController.java), [AccountDeletionService](../src/main/java/com/stock_spoon/river_be/account/service/AccountDeletionService.java), [Account](../src/main/java/com/stock_spoon/river_be/account/entity/Account.java), [Order](../src/main/java/com/stock_spoon/river_be/order/entity/Order.java).

## 2. 실행 순서 설명

계좌 삭제 요청은 `account.controller` 패키지의 `AccountController.delete()`로 들어온다. 인증된 JWT는 `jwt`, 경로의 계좌 ID는 `accountId` 인자로 전달된다. 컨트롤러는 요청 본문에서 회원 ID를 받지 않고 JWT subject를 내부 회원 ID로 변환한 후 `AccountDeletionService.delete(userId, accountId)`를 호출한다.

새 `account.service.AccountDeletionService`는 DB 트랜잭션을 시작하고 `AccountRepository.findLockedById(accountId)`로 계좌를 잠근다. 활성 조건이 없는 조회이므로 이미 비활성인 계좌도 찾을 수 있다. 계좌가 없거나 소유자가 JWT 회원과 다르면 같은 `404 ACCOUNT_NOT_FOUND`로 거절한다. 소유권 확인 후 `findCompetitionByAccountId()`로 대결 참가 여부를 검사한다. 사용자·AI 역할이나 대결 상태와 관계없이 연결이 있으면 `409 COMPETITION_ACCOUNT_DELETE_NOT_ALLOWED`로 거절하며 어떤 주문도 변경하지 않는다.

일반 계좌이면 `OrderRepository.findAllByAccountIdAndStatus(accountId, PENDING)`로 대기 주문 전체를 읽는다. 서비스는 현재 시각 `now`를 한 번 구해 각 `Order.cancel(now)`에 전달한다. 이 메서드는 상태를 `CANCELLED`로 변경하고 `reservedCash=0`, `cancelledAt=now`를 기록한다. 체결·기존 취소 주문은 조회 대상이 아니다. 기존 단건 취소 서비스는 대결 진행 상태도 검사하므로 호출하지 않고, 엔티티의 취소 동작을 재사용한다.

이후 `Account.deactivate()`가 `active=false`로 변경한다. 현금·보유 수량·운용 유형·소유자는 변경하지 않는다. JPA 변경 감지로 취소와 비활성화가 같은 트랜잭션에 반영되며, 중간 오류 시 함께 롤백된다.

서비스는 DB 커밋 후 큐 구성 여부를 `ObjectProvider<OrderRequestTransactions>`로 확인한다. 큐가 구성된 환경에서는 `cleanupInactiveAccount()`로 Redis에 남은 요청 결과를 정리한다. 이 단계가 실패하면 경고를 남기고 기존 reconcile에 맡긴다. 이미 커밋한 계좌 비활성화를 실패로 응답하지 않는다. 마지막으로 `OrderSubscriptionService.refresh()`가 남은 대기 주문을 기준으로 종목 구독 수요를 갱신한다. 컨트롤러는 서비스 반환 후 성공 JSON을 구성한다.

## 3. 단계별 전달값

| 메서드 | 입력 출처·값 | 결과 |
|---|---|---|
| `AccountController.delete(jwt, accountId)` | Access JWT, URL 계좌 ID | userId와 accountId를 서비스로 전달 |
| `AccountDeletionService.delete(userId, accountId)` | 인증된 회원 ID와 대상 계좌 | 소유권·대결 검사, 잠금, 취소·비활성화, 커밋 후 정리 |
| `OrderRepository.findAllByAccountIdAndStatus()` | accountId, `PENDING` | 매수·매도 및 주문 유형과 무관한 대기 주문 목록 |
| `Order.cancel(now)` | 서비스에서 한 번 구한 현재 시각 | 상태 `CANCELLED`, 예약 금액 해제, 취소 시각 기록 |
| `Account.deactivate()` | 잠금 조회한 계좌 | `active=false` |
| `cleanupInactiveAccount(accountId)` | 같은 계좌 ID | 비활성이면 미처리 요청 결과를 DB에 기록한 후 예약 전체 정리 |

## 4. 보존 정책

| 대상 | 처리 |
|---|---|
| 일반 사용자·일반 AI 계좌 | 비활성화 가능. 계좌 행은 보존 |
| 대결 사용자·대결 AI 계좌 | 대결의 READY/IN_PROGRESS/COMPLETED/CANCELLED 모두 개별 삭제 거절 |
| `PENDING` 매수·매도 주문 | 모두 취소, 예약 금액 해제 |
| 체결·기존 취소 주문, 체결 내역, AI 리포트 | 보존 |
| 현금·보유 종목 | 잔액·수량 그대로 보존. 자동 매도·이전 없음 |
| 마지막 활성 일반 계좌 | 비활성화 허용. 목록은 빈 배열, 온보딩 상태 유지 |
| 이미 비활성인 본인 일반 계좌 | 같은 성공 응답, 잔여 대기 주문·예약 정리 재시도 |
| 계좌 이름 | 비활성 계좌도 기존 `(user_id, name)` 유일 제약에 포함. 재사용 금지 |
| 복구 | 이번 API에 재활성화 기능 없음 |

기존 목록·상세·보유·주문 이력/요청 결과 접근은 활성 계좌 조건을 유지한다. 비활성 계좌는 목록에서 제외되고 상세는 404다. 비활성 계좌의 주문 요청 결과 조회는 기존 `FORBIDDEN_ACCOUNT` 403을 유지한다. 데이터 보존이 곧 별도의 이력 조회·복구 기능 제공을 뜻하지 않는다.

예: 현금 100만 원과 주식 5주가 있고, 매수/매도 대기 주문이 각각 한 건인 일반 계좌를 삭제하면 두 주문이 취소되고 계좌만 비활성화된다. 현금 100만 원과 보유 5주, 기존 체결·리포트는 남는다. 마지막 계좌여도 새 계좌를 만들 수 있지만 이전 계좌 이름은 재사용할 수 없다.

## 5. 후속 주문과 Redis/SQS

```text
SQS → OrderRequestListener.onMessage(message)
→ OrderRequestService.consume(m)
  → OrderRequestTransactions.discardIfAccountDeleted(m.accountId())
    → 탈퇴로 계좌가 없으면 잔여 예약을 지우고 이력 재생성 없이 반환
  → OrderRequestTransactions.rejectIfInactive(m)
    → 계좌 잠금·최신 상태 refresh
    → 비활성이면 기존 결과 확인 또는 REJECTED/ACCOUNT_INACTIVE 저장
  → 비활성 결과가 있으면 cleanup(m) 후 반환
→ 리스너 정상 종료 후 ON_SUCCESS ACK

기존 정리 주기 → OrderRequestService.reconcile()
→ discardIfAccountDeleted(accountId)로 삭제된 계좌의 예약 정리·재발행 생략
→ store.accounts() 각 계좌의 cleanupInactiveAccount(accountId)
  → 비활성 계좌의 예약별 기존 결과 확인 또는 거절 저장
  → DB 커밋
  → removeAll(accountId)
→ 비활성이면 재발행 생략
```

[OrderRequestTransactions](../src/main/java/com/stock_spoon/river_be/order/messaging/OrderRequestTransactions.java)의 `rejectIfInactive()`는 시세 조회 전에 실행하므로 장외이거나 호가 서비스가 실패해도 비활성 계좌의 메시지는 종결할 수 있다. 기존 처리 결과는 그대로 반환하고 미처리 요청에만 `ACCOUNT_INACTIVE`를 기록한다. 계좌·회원·요청 내용이 다른 동일 eventId 충돌 검증도 유지한다. 처리 중 `OrderException`이 발생하면 `OrderRequestService.consume()`은 계좌 삭제 여부를 다시 확인하고, 계좌가 남아 있으면 `rejectIfInactive()`로 다시 확인하여 시세 장애(503) 중 비활성화된 요청도 거절로 종결한다. 활성 계좌의 오류 분류와 `reject()` 내부 잠금 재검사도 유지한다. 삭제 확인 직후 탈퇴하여 비활성 검사에서 계좌 조회가 실패한 경우도 같은 예외 처리에 포함된다. 실제 처리의 기존 활성 계좌 검증도 유지한다.

`cleanupInactiveAccount()`는 비활성 상태를 잠금으로 확인한 후 예약별 거절 결과를 DB에 커밋하고 Redis를 삭제한다. DB 실패 시 예약을 먼저 버리지 않는다. Redis 삭제만 실패하면 DB 결과는 남고, 다음 reconcile이 기존 결과를 재사용해 정리한다. Redis/DB 장애를 비활성화 완료로 간주해 SQS 메시지를 ACK하지 않는다.

[RedisOrderReservationStore](../src/main/java/com/stock_spoon/river_be/order/reservation/RedisOrderReservationStore.java)의 `removeAll()`은 계좌 예약 해시와 계좌 인덱스 항목을 삭제한다. DB 계좌가 영구적으로 비활성이고 신규 예약 추가가 차단되어 있으므로 커밋 후 정리 동안 새 예약이 들어오지 않는다. 서버나 Redis가 복구되고 기존 큐 정리 작업이 실행되어야 후속 복구가 이루어진다. 회원 탈퇴 기능과 같은 예약 삭제 구현을 공유하지만, 탈퇴로 계좌가 삭제된 경우에는 거절 이력을 새로 만들지 않는다.

## 6. 체결과 동시에 요청했을 때

비활성화와 주문 접수·체결은 같은 계좌 행 잠금을 사용한다. 체결이 먼저 커밋되면 그 결과를 보존하고 남은 대기 주문만 취소한다. 비활성화가 먼저 커밋되면 `OrderExecutionService.executeLimit()`의 추가된 `isActive()` 검사에서 false를 반환하고 체결하지 않는다. 회원 탈퇴로 계좌나 주문이 삭제된 경우에도 false를 반환한다. 시장가 주문과 신규 접수의 기존 활성 계좌 검사도 유지한다. 이미 받아둔 시세 이벤트나 SQS 메시지가 비활성 계좌를 다시 거래시키지 못하도록 실제 실행 단계에서 검사한다.

## 7. 오류와 검증

| 조건 | 응답·결과 |
|---|---|
| 계좌 없음·타인 계좌 | 404 `ACCOUNT_NOT_FOUND` |
| 대결 연결 계좌 | 409 `COMPETITION_ACCOUNT_DELETE_NOT_ALLOWED` |
| 미인증 | 401 `UNAUTHORIZED` |
| CSRF 누락·불일치 | 403 `INVALID_CSRF_TOKEN` |
| DB 변경 실패 | 예외 전파, 주문 취소·비활성화 전체 롤백 |
| 커밋 후 예약 정리 실패 | 경고 로그, 비활성화 성공은 유지, 기존 reconcile 재시도 |

테스트는 H2/MySQL 모드와 실제 JPA·보안 필터를 사용한다. `AccountDeletionTests`는 자산·체결·리포트 보존, 대기 매수/매도 취소, 대결 양측 거절, 중복/마지막 계좌, 새 계좌 생성·이름 재사용 금지, CSRF/소유권, 중간 SQL 오류 롤백과 커밋 후 정리 실패를 확인한다. `OrderRequestTransactionsTests`는 실제 DB와 메모리 예약 저장소로 비활성 메시지 종결·중복 처리·예약 복구·자동 체결 차단 및 동시 체결/비활성화를 확인한다.

운영 MySQL·Redis·SQS에 실제 요청을 보내는 검증은 수행하지 않았다. 운영 환경의 잠금·장애 복구 검증은 별도다.

## 8. 패키지별 변경 요약

| 위치 | 변경 내용 |
|---|---|
| `account.controller.AccountController` | DELETE 메서드와 삭제 서비스 주입 추가 |
| `account.service.AccountDeletionService` | 비활성화 전담 서비스 추가. 기존 AccountService의 생성·조회 기능 재사용 |
| `account.entity.Account` | 자산을 보존하는 deactivate() 추가 |
| `execution.service.OrderExecutionService` | 지정가 실제 체결 시 활성 상태 검사 추가 |
| `order.messaging.OrderRequestTransactions` | 비활성 요청 거절·예약 결과 커밋 및 정리, lock()에서 잠금 계좌 반환 |
| `order.messaging.OrderRequestService` | 외부 시세 전 비활성 확인, 처리 중 재확인, reconcile 재발행 방지 |
| `order.reservation` | 계좌 단위 예약 전체 삭제 계약과 Redis 구현 추가 |
| `account.controller.AccountDeletionTests` | 신규 API·자산 보존·실패 경계 통합 테스트 |
| `order.messaging.OrderRequestTransactionsTests` | 비활성 메시지·복구·체결 경쟁 테스트 추가 |

추가·수정 블록에는 `[계좌 비활성화 추가/수정]` 주석을 남겼다.

최초 계좌 비활성화 구현 검증: `.\gradlew.bat test spotbugsMain --console=plain` 성공. 테스트 480개 중 462개 통과, 조건부 건너뜀 18개, 실패 0개. SpotBugs 통과. 별도 코드 리뷰에서 Critical/Important 지적은 없었다.

회원 탈퇴 기능이 포함된 dev와 PR #216의 충돌 해결 후 검증: `.\gradlew.bat test spotbugsMain --console=plain` 성공. 테스트 496개 중 478개 통과, 조건부 건너뜀 18개, 실패·오류 0개. SpotBugs 통과. 탈퇴 직후 비활성 검사와의 경합, 처리 중 503 오류와 비활성화가 겹치는 경우의 회귀 테스트를 추가했다. 회원 탈퇴 및 비활성화 기존 테스트도 모두 유지했다.
