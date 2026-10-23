# SQS 주문 생성 연동

## 현재 완료 상태

2026-10-23 기준 코드 구현과 `feat/170-sqs-order-creation` 브랜치의 작업 파일 반영을 완료했다. 커밋·push·배포는 하지 않았다. **기본 설정은 비활성화**이며 아래의 흐름은 `ORDER_QUEUE_ENABLED=true`일 때 적용된다.

- 전체 테스트: 458개 중 440개 통과, 18개 조건부 테스트 건너뜀, 실패·오류 0개.
- SpotBugs 검사와 `git diff --check` 통과.
- 실제 AWS 주문 큐, 운영 Redis/IAM, MySQL 연동 검증은 남아 있다. 실제 Redis 예약 테스트도 이번 실행에서는 건너뛰었다.

## 먼저 쉽게 이해하기

이 기능은 주문 요청이 한꺼번에 몰려도 SQS가 보관하고 BE가 정해진 동시성으로 처리하게 한다. 사용자는 앞에서 주문 가능 여부를 확인하고, Redis에 돈·주식을 임시로 잡아 둔다. 실제 주문을 DB에 만드는 일은 SQS에서 요청을 꺼낸 뒤 수행한다.

```text
사용자 → BE에서 검사 → Redis에 돈/주식 임시 예약 → SQS에 요청 넣기
               │                                      │
               └─ 부족하면 즉시 오류                   └─ 사용자에게 접수 완료 응답

AI ────────────────────────────────────────────────→ SQS에 직접 넣기

SQS → BE의 @SqsListener → 중복·권한·금액/수량 검사
                           ├─ 지정가: DB에 대기 주문과 예약 저장
                           └─ 시장가: 새 호가로 주문·체결·자산 변경 저장
                                      ↓
                         DB 저장 확정 → Redis 임시 예약 삭제
                                      ↓
                         정상 처리 종료 → SQS 메시지 삭제
```

BE의 Listener는 큐에서 요청을 꺼내 주문 생성 작업을 시작하는 코드다. 시세를 받아 지정가 체결을 판단하는 기존 `OrderExecutionListener`와는 역할이 다르다. 생성된 지정가 주문은 기존 시세 수신·체결 흐름으로 이어진다. 시장가는 소비 단계에서 전량 체결하거나 실패한다.

**접수 완료와 주문 생성 완료는 다르다.** 사용자는 접수 직후 eventId를 받고, 결과 조회에서 생성된 orderId 또는 거절 사유를 확인한다. eventId는 주문 생성 전부터 사용하는 요청 번호이고, orderId는 DB에 주문을 만든 뒤 생기는 주문 번호다.

## 현금 10만 원으로 보는 예시

1. 사용자가 7만 원 매수를 요청한다. BE가 DB와 Redis를 확인하고 가능하면 Redis에 7만 원을 예약한다.
2. 실제 현금 잔액은 아직 10만 원이지만, 다른 주문에 쓸 수 있는 돈은 3만 원이다. 7만 원짜리 두 번째 요청은 즉시 거절된다.
3. 첫 요청이 SQS에서 꺼내지면 DB에 지정가 주문과 7만 원 예약을 함께 저장한다.
4. 저장이 확정되면 Redis의 첫 요청 예약을 지운다. 예약이 Redis에서 DB로 넘어간 것이므로 가능 현금은 계속 3만 원이다.
5. DB 저장 직후 Redis 삭제가 잠시 실패해도, DB 처리 완료 기록을 보고 같은 7만 원을 두 번 빼지 않는다.

매도도 같다. 보유 10주에서 7주를 예약하면 다음 주문에는 3주만 팔 수 있다. 시장가 매수는 가격이 바뀔 수 있으므로 소비 시 새 호가로 금액을 다시 계산하고, 다른 주문의 예약을 보호한 여유 현금 범위에서만 추가 사용한다.

## 코드에서 따라가는 순서

패키지 루트는 `com.stock_spoon.river_be`다. 아래 메서드는 실제 구현 기준이다.

```text
POST /api/v1/accounts/{accountId}/orders
 → order.controller.OrderController.create(jwt, accountId, request, eventId)
 → order.messaging.OrderRequestService.submit(userId, accountId, eventId, request)
   → OrderRequestTransactions.validate(message)
   → 시장가라면 OrderSubscriptionService.withQuoteSnapshot(stockCode, callback)
   → OrderRequestTransactions.admit(message, quote)
     → AccountRepository.findLockedById(accountId)
     → DB 가능 자원·미처리 Redis 예약 검사
     → RedisOrderReservationStore.add(reservation)
   → OrderRequestPublisher.publish(message)
 → HTTP 202와 request_id

SQS 수신
 → order.messaging.OrderRequestListener.onMessage(message)
 → OrderRequestCodec.decode(body, sentAt)
 → OrderRequestService.consume(request)
   → 기존 결과 확인 / 시장가라면 최신 호가 확보
   → OrderRequestTransactions.process(request, quote, prices, validateQuote)
     → 지정가: OrderService.reserveLimit(userId, accountId, request, source)
     → 시장가: OrderExecutionService.executeMarket(...)
     → 주문 처리와 OrderRequestRecord를 같은 트랜잭션으로 저장·커밋
   → OrderRequestTransactions.cleanup(request)
   → 지정가라면 구독 갱신·OrderExecutionListener.orderCreated(order)
 → 정상 반환 후 ON_SUCCESS ACK
```

컨트롤러는 JWT에서 userId를, URL에서 accountId를, `Idempotency-Key` 헤더에서 eventId를, 본문에서 기존 OrderCreateRequest를 받는다. submit은 이를 공통 메시지로 바꾸고, admit은 계좌 잠금 안에서 검사와 Redis 예약을 수행한다. 잠금을 해제한 후 publish가 SQS에 전송하므로 SQS 통신을 기다리는 동안 DB 계좌를 잠그지 않는다.

소비 시 onMessage가 실제 SQS 메시지를 받아 decode로 요청을 복원한다. consume은 중복 결과를 먼저 확인하고, process가 지정가/시장가에 맞는 기존 서비스를 호출한다. DB 변경이 커밋된 뒤 cleanup이 Redis 예약을 정리한다. 일시 실패로 예외가 나면 정상 ACK까지 진행하지 않으며 메시지가 재수신되어도 DB의 eventId 기록으로 중복 주문을 막는다.

## 어떤 기존 파일을 수정했나

| 기존 파일 | 수정 이유 |
|---|---|
| `order/controller/OrderController.java` | 큐 활성화 시 사용자 접수를 새 서비스로 연결하고 202 응답. 기존 동기 경로는 비활성화 상태에서 유지 |
| `execution/service/OrderExecutionService.java` | 시장가 호가 계산을 공통 계산기로 추출. 기존 거래시간·호가·자산 검사 유지 |
| `order/service/OrderSubscriptionService.java` | 기존 주문 반환용 withMarketQuote를 유지하면서 예상 금액/결과 계산에도 쓸 수 있는 withQuoteSnapshot 추가 |
| `build.gradle` | Spring Cloud AWS SQS 추가. Windows 테스트 명령줄 제한을 피하도록 클래스패스 전달 변경 |
| `src/main/resources/application.yaml` | 기능 플래그·큐 URL·리전·소비 활성화 설정 추가 |
| `docs/redis/valkey-acl-requirements.md` | 주문 임시 예약에 필요한 키·명령 권한 안내 추가 |

기존 `OrderService`의 예약·취소 로직은 직접 수정하지 않고 재사용한다. 신규 코드는 `order/messaging`, `order/reservation`, 요청 처리 Entity/Repository, 결과 조회 Controller, `MarketOrderPricingService`에 추가했다. 신규 SQL은 `db/order-request-records.sql`이다. 기존 계좌·주문·보유·체결 테이블의 스키마는 변경하지 않았다.

## 변경되는 API

`ORDER_QUEUE_ENABLED=true`이면 USER의 기존 `POST /api/v1/accounts/{accountId}/orders`는 동기 HTTP 201 대신 **HTTP 202 접수**를 반환한다. FE는 요청마다 `Idempotency-Key` 헤더를 발급하고 네트워크 재시도에도 같은 값을 사용해야 한다. 허용 문자는 영문·숫자·`._:-`, 길이는 1~128이다.

```http
Idempotency-Key: 1e0181a0-d6e6-423a-af50-be42b5a48ab9
```

```json
{"request_id":"1e0181a0-d6e6-423a-af50-be42b5a48ab9","status":"accepted"}
```

사전 검증에서 현금·주식이 부족하면 즉시 기존 오류 형식으로 응답한다. 202는 Redis 임시 예약과 SQS 접수가 완료되었다는 뜻이다. 이후 최종 상태는 인증된 동일 계좌에서 조회한다.

`GET /api/v1/accounts/{accountId}/order-requests/{eventId}`

- `processing`: Redis에 임시 예약이 있고 아직 DB 최종 결과가 없음.
- `created`: `order_id` 포함. 기존 주문/체결 조회로 연결한다. 지정가는 PENDING일 수 있고 시장가 성공은 EXECUTED다.
- `rejected`: `reason_code` 포함. 최종 거절이므로 새 주문을 만들지 않는다.
- 알려진 기록이 없거나 소유 계좌가 다르면 조회를 허용하지 않는다. Redis 소실을 임의의 성공으로 응답하지 않는다.

전송 결과가 불분명하면 503 `ORDER_PUBLICATION_UNCERTAIN`으로 응답하고 임시 예약을 유지한다. 이 경우 동일 요청 ID로 조회/재시도해야 한다. 새 ID로 자동 재주문하면 별도 주문이 될 수 있다.

기능 비활성화 시 기존 동기 경로를 유지한다. 활성화 시 HTTP AI 생성은 `AI_ORDER_USE_QUEUE`로 거절하며 AI는 기존처럼 큐에 직접 전송한다. 취소·내역 조회 API는 유지한다.

## 확인된 AI 계약과 BE 메시지

사용자가 확인한 AI 계약은 최상위 `eventId`, `payload.account_id`, `payload.user_id`와 기존 주문 Body 필드다. AI 주체 필드는 없으므로 생략 시 AI로 해석하고 DB 계좌 운용 주체·소유권을 검증한다. `source`는 신뢰된 BE 발행 메시지에서 USER를 명시한다. IAM에서 허용된 발행 주체만 이 큐에 쓸 수 있어야 한다.

```json
{
  "eventId":"1e0181a0-d6e6-423a-af50-be42b5a48ab9",
  "correlationId":"1e0181a0-d6e6-423a-af50-be42b5a48ab9",
  "source":"USER",
  "requestedAt":"2026-10-22T01:00:00Z",
  "payload":{
    "account_id":123,"user_id":456,
    "stock_code":"005930","order_side":"buy","order_type":"limit",
    "limit_price":70000,"quantity":1
  }
}
```

AI가 correlationId를 생략하면 eventId를 사용한다. requestedAt을 생략하면 SQS 시스템 속성 SentTimestamp를 사용한다. timestamp에는 시간대가 필요하다. BE 발행은 원래 requestedAt을 재전송 시 보존한다. FIFO 그룹은 BE에서 accountId 문자열, 중복 제거 ID는 eventId다. AI의 기존 그룹 규칙은 별도 확인해야 하며 수신 시 임의의 AI 그룹명을 거절하지는 않는다. 계좌 잠금으로 자원 안전성은 보장하지만 여러 FIFO 그룹에 같은 계좌를 발행하면 요청 간 순서를 보장할 수 없다.

AI 시장가의 DLQ 재처리에서 새 전송 시각을 원래 접수 시각으로 오해하면 안 된다. 재처리 시 원래 requestedAt을 보존해야 한다. 이를 확인할 수 없는 과거 시장가 메시지는 다음 거래일에 재발행하지 않는다.

## 예약·체결과 장애 처리

USER 사전 접수는 DB 계좌 잠금 아래 DB 가능 금액/수량에서 Redis 임시 예약을 제외하고 검사한다. 통과하면 Redis Hash에 eventId별 예약을 기록한다. 기존 계좌·주문 테이블에 사전 예약 쓰기는 없다. 단, DB 조회·잠금과 Redis 쓰기 비용은 발생한다.

- 지정가 BUY: 지정가 × 수량, SELL: 종목별 수량 예약.
- 시장가 BUY: 여러 매도 호가 단계로 전량 예상 금액 계산. SELL: 종목별 수량 예약 및 전량 유동성 검사.
- SQS 소비 시 같은 계좌 잠금을 잡고 **다른 요청의 유효 Redis 예약을 보호**한다. 자신의 예약은 비용에서 중복 차감하지 않는다.
- 지정가는 기존 reserveLimit로 PENDING 주문/DB 예약과 최종 요청 결과를 함께 저장한다.
- 시장가는 새 호가로 전량 재계산 후 기존 executeMarket로 주문·체결·자산 변경과 최종 요청 결과를 함께 저장한다. 체결 전 호가 신선도·출처·거래시간·대결 상태 검사도 유지한다.
- 시장가 금액 상승은 다른 요청의 예약을 제외한 여유 현금까지 추가 사용한다. 부족하면 거절한다. 별도 버퍼·가격 보호 상한은 없다.
- 장 종료까지 미처리된 시장가는 거절 결과를 기록한 뒤 임시 예약을 해제한다. 늦은 재수신은 기존 결과를 사용해 다음 거래일 체결을 막는다.

DB 커밋 이후 Redis 예약을 지운다. 삭제 실패 시 다음 접수는 DB 최종 결과가 있는 eventId를 Redis 합계에서 제외한다. 소비 재시도/복구 작업이 남은 예약을 정리한다. 최종 결과가 있어도 같은 eventId에 다른 계좌·사용자·내용이 오면 충돌로 거절한다.

서로 다른 계좌가 동시에 같은 eventId를 접수한 경우 DB에서는 한 요청만 성공한다. 패배한 계좌의 정확히 일치하는 임시 예약만 해당 계좌 잠금 아래 해제한다. 승자 주문·결과는 수정하지 않으며 패배한 계좌의 결과 조회는 409 ORDER_REQUEST_CONFLICT다. 같은 계좌의 다른 내용으로 재사용한 메시지는 기존 유효 예약을 해제하지 않는다.

정상 처리 후 ON_SUCCESS ACK로 삭제한다. 업무 거절은 REJECTED 결과 기록까지 완료된 정상 처리로 ACK한다. 일시 장애·해석 불가능 메시지는 예외를 전파해 삭제하지 않는다. 직접 DLQ에 발행하거나 DLQ를 일반 소비자로 연결하지 않는다. 인프라가 업무 거절도 미삭제로 요구하는지는 배포 전에 확인한다.

30초 주기 복구 작업은 미전송/전송 결과 불명확 예약만 동일 eventId로 재발행한다. 전송이 확인된 메시지는 자동 재발행하지 않으며 SQS/DLQ 운영 절차에 맡긴다. 완료 요청의 잔여 예약과 장 종료된 시장가 예약도 정리한다. 단순 TTL로 미처리 예약을 없애지 않는다.

Redis 영속화·복제·noeviction 정책과 장애 후 복구 절차가 필요하다. Redis와 미전송 요청이 함께 소실되면 이 구현만으로 무손실 접수를 보장하지 못한다. Redis 장애 시 신규 접수는 실패하며 소비도 임시 예약을 건너뛰지 않는다.

## 배포 설정·스키마

**새 코드 배포 전에** `db/order-request-records.sql`을 적용한다. 신규 엔티티는 기능 플래그가 false여도 JPA 스키마 검증 대상이다. 기존 `trade_orders`, `accounts`, 보유·체결 테이블 스키마는 바꾸지 않는다. 신규 테이블은 eventId PRIMARY KEY로 중복 실행을 막고 계좌·소유자·입력 해시·결과·orderId·거절 코드·처리 시각을 보관한다. 결과는 원본 큐/DLQ 재처리가 가능한 동안 삭제하지 않는다.

```dotenv
ORDER_QUEUE_ENABLED=true
ORDER_QUEUE_CONSUMER_ENABLED=true
ORDER_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-order.fifo
AWS_REGION=ap-northeast-2
```

기본값은 ORDER_QUEUE_ENABLED=false다. 실제 연결 확인 없이 자동 전환하지 않는다. Consumer만 비활성화해도 복구 작업은 동작하므로 운영 점검 시 함께 고려한다. 롤백은 신규 접수 중단 → 큐/Redis의 잔여 요청 처리 방침 확정 → 소비 중단 → 동기 경로 재개 순서로 진행한다. 잔여 임시 예약이 있는 상태에서 동기 경로를 재개하면 예약을 무시할 수 있다.

AWS SDK 기본 자격 증명 체인을 사용하며 EC2 IAM Role로 인증한다. 키를 저장하지 않는다. 별도 SqsAsyncClient를 사용하므로 기존 DB 마이그레이션용 SqsClient는 변경하지 않는다. Long Polling 20초, 초기 Visibility Timeout 120초, 기본 소비 동시성 10이다. 실제 처리 지연에 맞춰 Visibility Timeout을 검토한다. MessageId·correlationId·eventId를 기록한다.

Redis 추가 권한:

- 읽기/쓰기: `order:temporary:*`
- Hash: HVALS, HGET, HSET, HSETNX, HDEL, HLEN
- 복구 인덱스 Set: SADD, SREM, SSCAN
- CAS 상태 갱신: EVAL/EVALSHA/SCRIPT LOAD

계좌별 Hash 키는 `order:temporary:{accountId}`, 복구 인덱스는 `order:temporary:accounts`다. 인덱스를 먼저 등록해 예약이 복구 검색에서 누락되지 않도록 한다. 모든 예약 추가/제거는 같은 계좌 DB 잠금 안에서 수행한다. Cluster에서도 단일 키 SSCAN으로 복구 대상을 찾으며 KEYS/전체 키공간 SCAN을 사용하지 않는다.

## 검증

```powershell
.\gradlew.bat test
.\gradlew.bat spotbugsMain
```

실제 로컬 Redis 검증은 전용 16380 포트에서만 수행한다.

```powershell
$env:ORDER_RESERVATION_REDIS_LIVE='true'
.\gradlew.bat test --tests '*RedisOrderReservationStoreLiveTests'
Remove-Item Env:ORDER_RESERVATION_REDIS_LIVE
```

AWS 개발 큐 발행/소비, EC2 역할, 배포 Redis ACL과 MySQL은 별도 검증 대상이다. 테스트에서 실제 주문 큐를 자동 호출하지 않는다.
