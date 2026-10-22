# 장중 시장가 주문 복구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지정가는 장외에도 예약 접수하고, 시장가는 장중에만 최신 호가로 전량 모의 체결한다.

**Architecture:** 기존 `OrderExecutionService`의 호가 단계별 체결 계산과 원자적 자산 변경을 재사용한다. `OrderController`에 시장가 분기를 복구하고, 요청 동안만 유효한 호가 구독 수요를 유지한다. 기존 AI 전용 전제를 USER/AI 출처 처리로 확장한다.

**Tech Stack:** Java 21, Spring MVC/Security/Data JPA, Redis MarketStream, JUnit/MockMvc, H2.

**Spec:** 이 대화에서 사용자가 명시한 정책: “지정가는 장외 생성·예약 가능, 시장가는 장중만 생성 가능.” 이 문서의 정책·호출 흐름을 구현 기준으로 사용한다.

## Global Constraints

- 작성 기준: `feat/178-stock-holding-detail`, HEAD `bdbac23`. 실행 전 최신 대상 브랜치와 변경 사항을 재확인한다. 이 계획 작성은 코드 수정·병합·배포 승인이 아니다.
- 장중 판정은 기존 규칙인 Asia/Seoul 평일 09:00 이상, 15:30 미만을 재사용한다. 공휴일·임시 휴장 달력 연동은 이번에 추가하지 않는다. 휴장 중 유효한 최신 호가가 없으면 체결하지 않는다.
- 지정가 상시 접수, 구독 장애에도 예약 유지, 다음 장중 시세로 체결하는 정책을 유지한다.
- 시장가는 예약하지 않는다. 성공하면 EXECUTED, 실패하면 신규 주문·체결·자산 변경이 모두 없어야 한다.
- JWT/CSRF, actor AI·USER 허용, 계좌 소유권·활성·대결 제한을 유지한다. 주문 주체와 계좌 유형 일치 정책도 이번 시장가 복구에서 임의로 변경하지 않는다.
- USER는 근거 없이 처리하고 AI는 기존 보고서 필수값을 유지한다. 출처는 요청 본문이 아닌 인증된 JWT로 결정한다.
- 주문 조회의 출처 필터 제거는 별도로 확정된 작업이다. 이 계획에서는 시장가를 이유로 조회 필터를 추가하지 않으며, 해당 작업의 완료 여부는 구현 시작 시 구분해 보고한다.
- 스키마·외부 의존성 추가 없음. 실제 증권사 주문 전송, 부분 체결 후 잔량 대기, 공유 호가 잔량 차감, 중복 요청 방지 기능은 추가하지 않는다.

## 현재 코드와 복구 근거

- `01448d0`에서 시장가 HTTP 분기를 제거하고 `MARKET_ORDER_DISABLED`를 추가했다. 해당 커밋 전체를 되돌리면 지정가 예약까지 되돌아가므로 필요한 흐름만 복구한다.
- `order/controller/OrderController.create()`는 현재 actor 검사 후 시장가를 무조건 거절한다.
- `execution/service/OrderExecutionService.executeMarket()`는 현재 호가 계산·전량 체결·롤백을 구현하지만 AI 계좌, reason 필수, AI 출처 저장을 전제로 한다.
- 시장가 서비스의 시간 검사는 현재 시각만 비교한다. 주말 및 자산 변경 직전 15:30 경계 재검사를 보강해야 한다.
- `order/entity/Order.pendingMarket()`는 AI 출처와 보고서 생성을 고정하므로 USER 처리에 맞게 확장해야 한다.
- `order/service/OrderSubscriptionService.refresh()`는 DB PENDING 주문만 구독한다. 시장가가 처음 요청된 종목의 구독을 확보하는 별도 수요가 필요하다.
- 현재 시세 타입은 `market.OrderBook`, `QuoteEvent`, `QuoteSource`다. 과거 키움 전용 타입을 그대로 복사하지 않는다.

## 복구 후 호출 흐름

```text
POST /api/v1/accounts/{accountId}/orders
 → Security 인증·CSRF → JSON 변환·@Valid
 → OrderController.create(jwt, accountId, request)
   → actor → source, sub → userId
   → buy/sell, limit/market 및 AI 보고서 필수값 검증
   → OrderService.assertOrderableAccount(userId, accountId, source)
   ├─ LIMIT
   │   → limitPrice 양수·호가단위 검사
   │   → 기존 subscriptions.create(... reserveLimit(...))
   │   → 기존 최초 체결 판단 → 201 pending 또는 executed
   └─ MARKET
       → limitPrice는 null, isTradingTime() 검사
       → OrderSubscriptionService.withMarketQuote(stockCode, execute)
           → 임시 구독 수요 확보, 최신 QuoteEvent 최대 3초 대기
           → OrderExecutionService.executeMarket(... source, book, prices, validate)
               → 계좌 잠금·권한·대결·시간 재검사
               → 최신 호가·잔량·자산 검증 및 체결 금액 계산
               → validate.run(): 시간·호가 신선도·구독 출처 재검사
               → 주문·체결·현금·보유량을 한 트랜잭션에 반영
               → EXECUTED 커밋
           → finally에서 임시 구독 수요 해제
       → OrderExecutionListener.response(accountId, orderId) → 201 executed
```

컨트롤러는 JWT·URL·본문에서 받은 값을 기존과 동일하게 해석하고 주문 유형으로 분기한다.
시장가는 양수 지정가를 요구하지 않고, 오히려 limit_price가 있으면 거절한다.
계좌 사전 검사를 통과한 장중 요청만 호가 구독을 확보한다. 호가를 기다리는 동안 DB 계좌 잠금을 잡지 않는다.
호가 확보 후 실행 서비스의 Spring 프록시를 호출해 트랜잭션을 시작한다. 자산 검증과 저장은 해당 트랜잭션 내부에서 처리한다.
서비스가 정상 반환하면 이미 커밋된 상태이며, 임시 구독을 해제하고 기존 응답 조회를 재사용한다.
시장가는 이미 체결했으므로 `orderCreated()`를 다시 호출하지 않는다.

## Review Focus

1. 기존 대기 주문이 전혀 없는 종목도 장중 시장가 요청으로 구독을 확보하고 체결할 수 있어야 한다. Task 2 테스트.
2. 같은 종목의 동시 시장가·지정가 요청에서 한 요청 종료가 다른 요청의 구독을 끊지 않아야 한다. Task 2 테스트.
3. 15:30 직전 요청이 호가·계좌 잠금을 기다리다 마감되면 어떤 자산도 변경하지 않아야 한다. Task 1·3 테스트.
4. 호가 수신 뒤 담당/세션 변경 또는 호가 만료가 발생하면 오래된 스냅샷으로 체결하지 않아야 한다. Task 1·2 테스트.
5. USER의 null 근거가 AI 보고서를 만들지 않고, 후속 체결 저장 실패는 주문과 모든 자산을 롤백해야 한다. Task 1·3 테스트.

## Task 1: 시장가 체결 서비스를 USER/AI 및 시간 경계에 맞게 보강

**Files:**
- Modify: `src/main/java/com/stock_spoon/river_be/execution/service/OrderExecutionService.java`
- Modify: `src/main/java/com/stock_spoon/river_be/order/entity/Order.java`
- Test: `src/test/java/com/stock_spoon/river_be/execution/service/MarketBuyTransactionTests.java`
- Test: `src/test/java/com/stock_spoon/river_be/execution/service/OrderExecutionServiceTests.java`

**Interfaces:** 기존 오버로드는 AI 기본값을 유지한다. HTTP용 진입점은 다음으로 확장한다.

```java
Order executeMarket(long userId, long accountId, OrderCreateRequest request,
    Order.Source source, OrderBook book, Map<String, BigDecimal> prices, Runnable validate)
```

`Order.pendingMarket()`의 가장 상세한 오버로드에 source를 추가하고 기존 오버로드는 AI를 전달한다.
USER는 reason을 null로 정규화하고 보고서를 생성하지 않는다. AI의 reason 필수·길이와 보고서 입력 검증은 유지한다.

- [ ] 실패 테스트부터 추가: USER 수동 계좌 매수·매도 성공, USER 출처·보고서 없음; AI 기존 동작 유지; 계좌 소유권·유형 불일치 거부.
- [ ] 고정 Clock 테스트 추가: 08:59:59 거부, 09:00 허용, 15:29:59 허용, 15:30 거부, 주말 거부. 마지막 검증 중 마감에 도달하는 경우와 실제 계좌 잠금 대기 중 마감되는 경우도 저장·자산 변화 0을 검증한다.
- [ ] 위 테스트를 실행해 기존 AI 전제 및 시간 검사의 부족으로 실패함을 확인한다.
- [ ] 기존 `inTradingHours()`를 사용해 잠금 후와 자산 반영 직전 시간을 재검사한다. 마지막 검사에서 얻은 시각을 createdAt/체결 시각에 사용한다.
- [ ] 호가 검증을 공통 내부 메서드로 정리해 잠금 후 및 자산 반영 직전에 같은 규칙으로 검사한다: 종목 일치, 당일 수신, 수신 나이 5초 이내·미래 수신 거부, 호가 시각 과거 5초/미래 1초 허용 범위, exchangeTimestamp가 있으면 시간 일치, 양수 정수 가격과 비음수 잔량. `validate.run()`으로 구독 세션/출처도 다시 확인한다.
- [ ] 기존 매수 asks 오름차순·매도 bids 내림차순 소비를 재사용한다. 70,000원 1주 + 70,100원 2주의 매수는 3주/210,200원/체결 2행을 독립 기대값으로 검증한다. 잔량 부족은 부분 저장 없이 실패한다.
- [ ] 기존 대기 주문 예약 현금·수량을 제외한 가용 자산 계산, 동시 주문 보호, 후속 체결 저장 실패 전체 롤백 테스트를 실행한다.
- [ ] 실행: `.\gradlew.bat test --tests '*MarketBuyTransactionTests' --tests '*OrderExecutionServiceTests' --console=plain` → 실패 0. 검증 후 작업 단위 커밋.

## Task 2: 요청 동안 유지하는 시장가 호가 구독

**Files:**
- Modify: `src/main/java/com/stock_spoon/river_be/order/service/OrderSubscriptionService.java`
- Test: `src/test/java/com/stock_spoon/river_be/order/service/OrderSubscriptionServiceTests.java`

**Interfaces:**

```java
Order withMarketQuote(String stockCode, Function<QuoteEvent, Order> execute)
```

컨트롤러가 전달한 execute는 Task 1의 트랜잭션 서비스를 호출한다. QuoteEvent는 source와 OrderBook을 함께 보존한다.

- [ ] 신규 종목에서 요청 중 구독 확보, 호가 도착 후 callback 실행, 성공·실패·timeout 후 해제 테스트를 먼저 작성하고 실패를 확인한다.
- [ ] 서비스 내부에 종목별 임시 요청 수를 유지한다. `refresh()` 구독 대상은 `DB PENDING 종목 ∪ 임시 요청 중 종목`으로 변경한다. 수요 증감과 구독 갱신은 같은 동기화 규칙으로 보호하되 호가 대기·callback 실행 중에는 서비스 잠금을 보유하지 않는다.
- [ ] 단조 시간 기준 최대 3초 동안 50ms 간격으로 `stream.latestBookQuote(stockCode)`를 확인한다. 값이 있으면 `assertSource(stockCode, quote.source())`를 검사하고 callback에 전달한다. 3초는 연결·도착 대기 상한이며, 최종 호가 5초 규칙을 대체하지 않는다. interrupt는 interrupt 상태를 복원하고 실패 처리한다.
- [ ] 구독 연결 실패·3초 내 호가 미도착은 `503 MARKET_STREAM_UNAVAILABLE`. 확보된 호가의 시간·가격 오류는 Task 1에서 `503 MARKET_DATA_UNAVAILABLE`. Redis 소스 메타데이터 없는 운영 호가는 수락하지 않는다.
- [ ] finally에서 해당 요청의 수요만 감소시키고 구독을 갱신한다. 실패 시 남은 임시 수요와 기존 PENDING 수요를 보존한다. 정리용 구독 갱신 실패로 이미 성공한 거래를 HTTP 실패로 바꾸지 않는다.
- [ ] 동일 종목 동시 요청 중 하나가 종료되어도 다른 요청 종료 전에는 구독이 남는지, 주기 refresh가 요청 중 종목을 제거하지 않는지 검증한다. 호가 출처 변경 시 callback이 저장하지 못하는지도 검증한다. 시간 대기는 테스트에서 제어 가능하게 해 실제 긴 sleep을 반복하지 않는다.
- [ ] 실행: `.\gradlew.bat test --tests '*OrderSubscriptionServiceTests' --console=plain` → 실패 0. 검증 후 작업 단위 커밋.

## Task 3: 컨트롤러 시장가 분기 복구와 실패 추적

**Files:**
- Modify: `src/main/java/com/stock_spoon/river_be/order/controller/OrderController.java`
- Test: `src/test/java/com/stock_spoon/river_be/order/service/OrderControllerTests.java`

**Interfaces:** Task 1의 source 포함 executeMarket, Task 2의 withMarketQuote, 기존 `OrderBuyPriceService.fetch(accountId, stockCode)`와 `OrderExecutionListener.response(accountId, orderId)`를 사용한다.

- [ ] MockMvc에서 USER/AI 각각 장중 시장가 매수·매도 `201 executed`, limit_price null, 출처·보고서 정책을 검증하는 실패 테스트부터 작성한다. 기존 무조건 시장가 거부 테스트는 장외 거부 및 지정가 입력이 있는 시장가 거부로 바꾼다.
- [ ] `MARKET_ORDER_DISABLED` 분기를 제거한다. 공통 actor/방향/AI 입력 검증과 계좌 사전 검사는 유지한다. 시장가는 limit_price null, 지정가는 기존 양수·호가단위 검사를 적용한다.
- [ ] 시장가 분기에서 `marketExecution.isTradingTime()`이 false면 `409 ORDER_WINDOW_CLOSED`로 즉시 거부한다. 호가 구독·자산 변경은 발생하지 않아야 한다.
- [ ] AI 매수의 보고서 평가에 필요한 다른 보유 종목 가격만 기존 buyPrices.fetch로 확보한다. 이 REST 가격은 시장가 체결 가격으로 사용하지 않는다. USER 및 매도는 빈 prices를 전달한다.
- [ ] `withMarketQuote()` callback 안에서 QuoteEvent의 book·source를 보존하고, `stream.assertSource()` 실패를 `503 MARKET_STREAM_UNAVAILABLE`로 변환하는 validate를 전달해 executeMarket을 호출한다.
- [ ] 트랜잭션 서비스 정상 반환 후 시장가 완료 로그를 기록한다: `event=market_order_completed orderId accountId stockCode side source`. 실패는 시장가 분기에서 `event=market_order_rejected stage code accountId stockCode side source`로 기록하고 같은 예외를 전파한다. stage는 `trading_time`, `quote_acquisition`, `execution`으로 구분한다. 기존 requestId를 사용하고 토큰·본문·근거 원문은 기록하지 않는다. 공통 INVALID_ORDER에 대해서도 잔고/수량/잔량 등 실제 거부 원인이 구분되도록 시장가 서비스의 해당 예외에 전용 코드 `INSUFFICIENT_CASH`, `INSUFFICIENT_SELLABLE_QUANTITY`, `INSUFFICIENT_MARKET_LIQUIDITY`를 부여한다.
- [ ] 계좌 소유권·유형·대결 실패, 호가 미도착/만료/세션 변경, 현금·수량·상대 잔량 부족을 API로 검증한다. 실패 시 주문·체결 행과 잔액·보유량이 요청 전과 동일해야 한다.
- [ ] 장중 성공·장외 실패·마감 중 실패와 로그의 stage/code를 고정 Clock·외부 시세 경계 mock으로 검증한다. 실제 주문 API/증권사로 요청하지 않는다.
- [ ] 기존 지정가 야간·주말 예약, 구독 장애 접수, 취소·체결 테스트를 함께 실행한다: `.\gradlew.bat test --tests '*OrderControllerTests' --tests '*OrderExecution*Tests' --tests '*OrderSubscriptionServiceTests' --console=plain` → 실패 0. 검증 후 작업 단위 커밋.

## Task 4: 문서 일치 및 전체 검증

**Files:**
- Modify: `docs/stockspoon_order_create.md`, `docs/reserved-limit-orders.md`, `docs/cloudwatch-logging.md`
- Modify: `docs/stockspoon_order_execution.md`의 현재 정책 안내

- [ ] 시장가 전면 차단 설명을 장중만 허용으로 바꾸고, USER 시장가 요청 예시 `{"stock_code":"005930","order_side":"buy","order_type":"market","limit_price":null,"quantity":1}`를 추가한다.
- [ ] 장외 시장가 거부·주문 미저장, 유효 호가 3초 대기 및 신선도 규칙, 전량 체결·롤백, 지정가 예약 유지, CloudWatch 실패 코드 검색을 문서화한다.
- [ ] 호출부·기존 오버로드·테스트 mock·생성자 주입을 검색해 변경 누락이 없는지 확인한다. 운영 타입을 과거 키움 전용 타입으로 되돌리지 않았는지 검토한다.
- [ ] `.\gradlew.bat build --build-cache --console=plain` 및 `git diff --check`를 실행한다. 전체 테스트 실패 0, SpotBugs 통과를 확인하고 건너뛴 외부 테스트 수를 보고한다.
- [ ] 독립 코드 검토에서 마감 경계, 잠금 대기, 구독 수요 정리, USER 보고서 없음, 롤백을 우선 확인한다.
- [ ] 코드·문서·테스트를 검증 후 커밋한다. 원격 푸시·병합·배포는 실행 시 사용자가 지정한 범위를 따른다. 실제 Redis/시장 연동을 검증하지 않았다면 완료 보고에 명시한다.

## 수용 기준

| 입력/환경 | 기대 결과 |
|---|---|
| 장중 USER 수동 계좌 시장가, 유효 호가·충분한 자산 | 201 executed, USER 출처, 보고서 없음 |
| 장중 AI AI계좌 시장가, 근거·보고서 필수값 정상 | 201 executed, AI 출처·보고서 유지 |
| 시장가에 limit_price 제공 | 400, 저장 없음 |
| 장외·주말 시장가 | 409 ORDER_WINDOW_CLOSED, 저장 없음 |
| 호가 대기·잠금 대기 중 15:30 도달 | 409 ORDER_WINDOW_CLOSED, 저장·자산 변경 없음 |
| 호가 없음·구독 실패 | 503, 저장 없음 |
| 만료·미래·다른 종목·잘못된 가격의 호가 | 503 MARKET_DATA_UNAVAILABLE, 저장 없음 |
| 호가 출처 세션 변경 | 503 MARKET_STREAM_UNAVAILABLE, 저장 없음 |
| 현금·매도 가능 수량·상대 호가 잔량 부족 | 400 및 구분 가능한 원인 코드, 부분 체결 없음 |
| 후속 체결 DB 저장 실패 | 주문·체결·현금·보유량 전체 롤백 |
| 장외 지정가·시세 연결 장애 | 기존과 같이 201 pending, 예약 유지 |

## 실행 기록

- 2026-10-22: 사용자 요청에 따라 한 번에 한 단계만 실행한다. Task 1 이후 멈추고 결과를 설명한다.
- 실행 기준 HEAD는 작성 당시와 달리 `dev`의 `c16f3b7`이며, 여기서 `codex/restore-market-orders`를 생성했다. 작업 중 공용 dev와 원격은 변경하지 않는다.
- Task 1 완료: USER/AI 출처를 받는 시장가 진입점 및 엔티티 생성 오버로드 추가, USER 보고서 생략, 계좌 소유권·유형 검증 유지, 평일 장중 판정과 저장 직전 시간·호가 재검사 적용.
- 검증 기록: 주말·최종 검증 중 마감·최종 검증 중 호가 만료 테스트 3개가 기존 코드에서 실패함을 확인했다. USER 신규 진입점 테스트는 해당 오버로드가 없는 상태에서 컴파일 실패를 확인한 뒤 구현했다. 관련 시장가/지정가 서비스 테스트 통과 후 전체 `gradlew.bat build --build-cache --console=plain` 성공(SpotBugs 포함).
- Task 1 추가 검증: USER 시장가 매수·매도 자산 계산·보고서 없음, 출처/계좌 불일치·타인 소유권·AI 보고서 필수값 거부, 09:00/15:29:59 허용, 실제 계좌 잠금과 마감 경계, 기존 동시 주문 및 저장 실패 롤백 테스트.
- Task 2~4 미실행. 컨트롤러의 시장가 차단은 아직 유지하며 실제 API 복구 완료가 아니다. 실제 시세/Redis 연동과 배포 검증은 하지 않았다.
