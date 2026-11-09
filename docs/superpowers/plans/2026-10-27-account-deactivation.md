# 계좌 삭제 요청 시 비활성화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 본인 일반 계좌의 삭제 요청을 받으면 모든 대기 주문을 취소하고 계좌를 비활성화한다. 자산과 투자 기록은 보존하고 대결 연결 계좌의 개별 삭제는 거절한다.

**Architecture:** 기존 AccountController에 DELETE를 추가하고, 새 AccountDeletionService 하나가 소유권·대결 여부 검증, 계좌 잠금, 대기 주문 취소, 비활성화를 총괄한다. DB 변경은 TransactionTemplate로 한 번에 커밋하고 이후 Redis 예약·시세 구독을 정리한다. 별도 작업 테이블·스케줄러는 추가하지 않는다.

**Tech Stack:** 기존 Spring MVC/JPA, 관계형 DB, Redis, SQS, JUnit/MockMvc.

**Spec:** 이 대화에서 확정한 비활성화·대기 주문 취소·자산 보존·대결 계좌 개별 삭제 금지 정책. 마지막 계좌, 이름 재사용 등 아래 미확정 항목은 제안이다.

## 확정 정책

- DELETE /api/v1/users/me/accounts/{accountId}. JWT 회원 ID와 경로의 계좌 ID만 사용한다. 본문은 없다.
- 성공 응답은 제공된 명세에 맞춰 200과 {"message":"delete_success"}를 사용한다.
- 일반 사용자 계좌와 대결에 연결되지 않은 일반 AI 계좌가 대상이다.
- PENDING 주문은 모두 CANCELLED로 바꾸고 reservedCash=0, cancelledAt을 기록한다. 체결·기존 취소 주문은 바꾸지 않는다.
- Account.active=false로 변경한다. 현금·보유 종목·주문 이력·체결·AI 리포트는 물리 삭제하거나 이전하지 않는다.
- 대결 사용자/AI 계좌는 대결 상태와 관계없이 개별 삭제를 거절한다. 대결·참가자도 변경하지 않는다.
- 목록·상세의 기존 active=true 조건으로 비활성 계좌를 제외한다.
- Redis나 SQS가 지연되더라도 비활성 계좌에서 주문을 생성하거나 체결할 수 없어야 한다.

## 진행 승인에 따라 적용한 구현 기본값

- 마지막 활성 일반 계좌도 비활성화 허용. 계좌 목록은 []이며 onboardingCompleted는 유지해 새 계좌를 생성할 수 있다.
- 이미 비활성인 본인 일반 계좌의 재요청은 같은 200 응답. 남은 예약 정리를 다시 시도한다.
- 비활성 계좌 이름 재사용은 현재 DB의 (user_id, name) 유일 제약대로 금지한다.
- 계좌 복구 API는 이번 범위에서 제공하지 않는다. 기존 조회 API도 비활성 계좌의 이력을 공개하지 않는다.
- 타인 소유/없는 계좌는 404 ACCOUNT_NOT_FOUND, 대결 연결 계좌는 409 COMPETITION_ACCOUNT_DELETE_NOT_ALLOWED.

## 파일별 변경

패키지 루트: src/main/java/com/stock_spoon/river_be/.

| 파일 | 변경 |
|---|---|
| account/controller/AccountController.java | delete(jwt, accountId) 추가. AccountDeletionService.delete(userId, accountId) 호출 후 기존 명세 JSON을 반환한다. |
| account/service/AccountDeletionService.java (신규) | 삭제 요청에 필요한 검증·트랜잭션·커밋 후 정리를 담당한다. 기존 AccountService의 생성·조회 로직은 유지한다. |
| account/entity/Account.java | deactivate() 추가. 잔액·보유·운용 유형·소유자를 변경하지 않고 active만 false로 만든다. |
| account/repository/AccountRepository.java | 기존 findLockedById(), findCompetitionByAccountId()를 재사용한다. 활성 조건 없이 조회해야 중복 요청과 소유권을 확인할 수 있다. |
| order/repository/OrderRepository.java | 기존 findAllByAccountIdAndStatus(accountId, PENDING) 재사용. |
| order/entity/Order.java | 기존 cancel(now)를 재사용한다. 기존 단건 취소 서비스는 대결 진행 상태를 검사하므로 반복 호출하지 않는다. |
| execution/service/OrderExecutionService.java | executeLimit()에서 계좌 잠금 후 active=false이면 false 반환. 시장가의 기존 활성 검증은 유지한다. |
| order/reservation/OrderReservationStore.java 및 Redis 구현 | 계좌 단위 removeAll(accountId) 추가. Redis 해시와 계좌 인덱스를 정리한다. |
| order/messaging/OrderRequestTransactions.java | 실제 처리 트랜잭션에서 활성 여부를 재검사하고, 미처리 요청을 ACCOUNT_INACTIVE로 종결하는 경로를 추가한다. |
| order/messaging/OrderRequestService.java | 비활성 계좌의 지연 메시지를 외부 시세 조회 전에 종결·정리한다. 기존 reconcile에서도 비활성 계좌의 예약을 재발행하지 않는다. |
| account/exception/AccountExceptionHandler.java | 기존 AccountException 변환을 재사용하므로 새 예외 처리기는 만들지 않는다. |

## 실행 흐름

1. 인증·CSRF 검증 후 AccountController.delete()가 JWT subject와 accountId를 서비스에 전달한다.
2. AccountDeletionService.delete()가 DB 트랜잭션을 시작하고 계좌를 잠금 조회한다. 타인 소유/없는 계좌는 같은 404로 거절한다.
3. 대결 참가 연결이 있으면 409로 거절한다. 여기까지 주문과 계좌 상태는 바꾸지 않는다.
4. 해당 계좌의 PENDING 주문을 읽고 동일 now로 Order.cancel(now)를 호출한다. 이미 체결/취소된 주문은 유지한다.
5. Account.deactivate()를 호출하고 커밋한다. 중간 오류가 나면 주문 취소와 비활성화 모두 롤백한다.
6. 구성된 Redis 예약 저장소에서 계좌 예약을 정리하고 OrderSubscriptionService.refresh()를 호출한다. 저장소는 큐가 꺼진 환경에서는 선택적으로 사용한다.
7. 커밋 후 Redis 정리 실패는 경고로 기록하고 기존 reconcile에서 재처리한다. DB 성공을 롤백했다고 응답하지 않는다.
8. Controller가 200과 delete_success를 반환한다.

## 주문·예약과 경쟁 실행

- 모든 주문 접수/체결/취소는 같은 계좌 잠금과 협력한다. 체결이 먼저 커밋되면 결과를 보존하고 나머지 PENDING만 취소한다. 비활성화가 먼저 커밋되면 후속 주문·체결은 거절한다.
- 신규 주문 접수뿐 아니라 SQS 실제 처리와 executeLimit()에서도 비활성 상태를 확인한다.
- Redis에만 존재하던 요청은 DB 주문이 아니므로 Order.cancel() 대상이 아니다. 예약을 해제하고 지연 메시지는 REJECTED/ACCOUNT_INACTIVE로 종결한다.
- 비활성 계좌의 메시지는 일반 오류로 끝없이 재시도하지 않는다. 이미 저장된 요청 결과는 그대로 유지하고 중복 메시지로 덮어쓰지 않는다. 타 계좌의 동일 eventId 충돌 검증도 유지한다.
- 비활성 상태 판정과 종결 기록 저장은 잠금 트랜잭션으로 실행한다. DB 장애나 Redis 장애를 정상적인 비활성 처리 완료로 오인해 ACK하지 않는다.
- 기존 reconcile은 비활성 계좌의 미처리 예약을 종결하고 정리한다. 계좌/요청을 지울 때 단순 removeAll만 수행해 영구적인 processing 상태를 만들지 않는지 확인한다. 단, 비활성 계좌 결과 조회는 기존 권한 정책으로 차단될 수 있음을 문서화한다.

## Review Focus

- 대결 계좌 거절 시 상대 계좌·주문·대결에 부작용이 없어야 한다.
- 현금/보유가 있는 마지막 계좌도 제안한 정책대로 처리되고 온보딩은 유지되어야 한다.
- 취소와 비활성화는 함께 커밋/롤백되어야 한다.
- 늦은 시세 및 SQS가 비활성 계좌의 주문을 실행하면 안 된다.
- 재요청과 중복 메시지가 기존 체결/거절 결과를 덮어쓰면 안 된다.

## Task 1: 비활성화 API와 트랜잭션

- [x] HTTP 통합 테스트부터 작성한다: 본인 일반 계좌(현금·보유 있음), 모든 대기 주문 취소, 체결/리포트/보유 보존, 목록·상세 제외.
- [x] 대결 사용자/AI 계좌는 모든 대결 상태에서 409이고 데이터가 그대로인지 확인한다.
- [x] 소유권·미인증·CSRF, 마지막 계좌·중복 요청·이름 재사용 정책을 테스트한다. 위의 적용 기본값을 기준으로 검증한다.
- [x] Controller, 신규 AccountDeletionService, Account.deactivate()를 구현하고 변경 블록에 한국어 주석을 붙인다.
- [x] 중간 오류를 주입해 취소와 비활성화의 전체 롤백을 확인한다.

## Task 2: 후속 주문 차단과 예약 정리

- [x] 먼저 실패하는 테스트로 비활성 계좌의 지정가 자동 체결을 재현하고 executeLimit()을 보완한다.
- [x] 계좌 단위 예약 정리와 비활성 메시지 종결을 구현한다. 시세 미제공 환경에서도 비활성 메시지는 종료되는지 검증한다.
- [x] 지연/중복 SQS, Redis 장애 후 reconcile 복구, eventId 충돌, 동시 체결/비활성화를 검증한다.

## Task 3: 문서 및 전체 검증

- [x] docs/stockspoon_account_flow.md를 실제 Controller -> Service -> Repository/Entity -> 커밋 후 정리 순서로 갱신한다. 자연어 설명·전달값 표·성공/오류 JSON·보존 데이터를 포함한다.
- [x] 관련 테스트 후 전체 Gradle 테스트를 실행하고 git diff --check와 별도 코드 리뷰를 수행한다.
- [x] 운영 Redis/SQS/MySQL을 실제 검증하지 못한 경우 그 한계를 명시한다. 커밋·push·배포는 별도 요청 없이 수행하지 않는다.

## 현재 상태

구현·테스트·주석·문서 작성 완료. 전체 테스트 480개 중 462개 통과, 18개 건너뜀, 실패 0개 및 SpotBugs 통과. 운영 서비스 검증은 별도다. 이전 계좌 목록 변경은 현재 브랜치에 별도 커밋으로 존재한다.

구현 조정: 커밋 후 정리는 OrderReservationStore를 직접 호출하는 대신 OrderRequestTransactions.cleanupInactiveAccount()에 위임한다. 예약의 거절 결과를 DB에 먼저 커밋한 뒤 Redis를 지우기 위해서다.
