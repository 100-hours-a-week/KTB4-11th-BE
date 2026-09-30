# 지정가 주문 자동 체결 구현 과정과 코드 흐름 (v1)

## 작업 개요

- 목표: 주문을 저장하는 기능에 체결 처리를 연결하여, 현재가가 지정가 조건을 만족하면 현금·보유종목·체결 이력·주문 상태를 함께 변경한다.
- 범위: BE 내부 모의투자의 지정가 전량 체결, 주문 생성 직후 가격 판단, 웹소켓 수신에 따른 판단, 체결 후 구독 정리.
- 기준 코드: 브랜치 `feat/23-order-execution`, HEAD `0692359` 위의 미커밋 작업 트리.
- 작성일: 2026-10-01.
- 이전 작업: [주문 생성 흐름](stockspoon_order_create_flow.md), [주문 구독과 장 마감 취소](stockspoon_order_websocket_lifecycle.md).
- 이 문서의 “체결”은 스톡스푼 DB에서 이루어지는 모의 매매다. 키움에는 시장데이터만 요청하며 실제 증권 주문 API는 호출하지 않는다.

## 진행 순서

1. 이번 단계의 체결 정책과 구현 범위 확정
2. 체결 이력 엔티티와 MySQL 테이블 추가
3. 지정가 조건 판단과 자산 변경을 하나의 트랜잭션으로 구현
4. 취득원가를 양수로 제한
5. 주문 취소 조회 오류 수정과 내부 체결 검증
6. 주문 생성·웹소켓 현재가를 체결 서비스에 연결
7. 연결 흐름과 실패 격리를 자동 테스트로 검증
8. 장외에 가능한 실제 키움 연동 검증

단계별 본문은 실제 작업 과정을 설명한다. 뒤의 **현재 코드 전체 흐름**은 모든 단계가 반영된 현재 동작을 설명한다.

## 단계 1. 체결 정책과 범위 확정

### 당시 코드 상황

주문 생성과 대기 주문의 시세 구독, 장 마감 취소는 구현되어 있었다. 하지만 받은 가격을 주문 조건과 비교해 자산을 바꾸는 서비스와 체결 이력은 없었다. 주문 테이블의 상태만 바꾸면 현금과 보유종목은 그대로이므로 실제 모의 매매가 완성되지 않는다.

### 결정과 근거

사용자와 다음 정책을 확인했다.

| 항목 | 이번 구현 기준 |
| --- | --- |
| 체결 진입점 | 별도 HTTP 체결 API 없이 BE 내부에서 호출 |
| 매수 | 현재가가 지정가 이하이면 체결 |
| 매도 | 현재가가 지정가 이상이면 체결 |
| 가격·수량 | 관측된 현재가로 주문 수량 전체 체결 |
| 지정가 체결 이력 | 주문 한 건당 체결 한 건 |
| 수수료·세금 | 계산하지 않음 |
| 취득원가 | 평균취득원가 사용 |
| 처리 시간 | 한국 시간 09:00 이상 15:30 미만 |
| 마감·날짜 경과 | 미체결 주문 취소, 다음 거래일로 이월하지 않음 |
| 거래정지·휴장일 | 이번 v1 구현에서 검사하지 않음 |
| 시장가 | 이번 작업 범위에서 제외 |

예를 들어 70,000원 지정가 매수 주문이 있을 때 현재가가 69,000원이면 **69,000원으로** 체결한다. 지정한 70,000원은 조건과 현금 예약에 사용하는 값이다.

### 구현 내용

이 정책을 이후 엔티티, 서비스, 테스트의 기준으로 사용했다. 정책 문서의 거래정지 등 일부 내용은 사용자와 확인한 최신 결정이 우선한다.

### 참고 자료

- 사용자 제공 `03_주문_체결_정책_v1.md`: 지정가 조건, 전량 체결, 평균취득원가, 실현손익, 당일 주문 기준.
- 사용자 확정 대화: 수수료 미고려, 거래정지 검사 제외, 내부 체결 처리.
- [API 설계 문서](https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152): 체결 필드명 논의의 기준. 외부 문서의 모든 내용이 구현되었다는 의미는 아니다.

### 결과 및 확인

지정가 체결을 구현할 기준을 확정했다. 실제 거래소 장운영 여부나 시장가 체결을 확인하는 단계는 아니다.

## 단계 2. 체결 이력과 테이블 추가

### 당시 코드 상황

기존 주문은 `trade_orders`에 저장되었다. 체결가격과 체결수량을 기록할 엔티티가 필요했다. 로컬 설정의 `ddl-auto=validate`는 엔티티와 테이블이 맞는지 검사할 뿐, 테이블을 만들어 주지 않는다.

### 결정과 근거

체결은 주문과 연결되는 별도 이력으로 저장했다. 지정가는 한 건의 체결만 만들지만, 구조상 주문 1건에 여러 체결을 연결할 수 있다. 이 관계는 향후 호가 단계별로 체결되는 시장가에도 사용 가능하며, 이번에 시장가 로직을 구현한 것은 아니다.

ERD의 테이블 이름 `executions`와 API의 체결 필드를 참고했다. 기존 주문 테이블 이름 `trade_orders`는 유지하여 외래키가 실제 테이블을 가리키게 했다.

### 구현 내용

- [Execution.java](../src/main/java/com/stock_spoon/river_be/order/Execution.java): 체결 한 건을 나타내는 JPA 엔티티.
- [ExecutionRepository.java](../src/main/java/com/stock_spoon/river_be/order/ExecutionRepository.java): 체결 저장과 주문별 체결 조회.
- [order_execution.sql](../db/order_execution.sql): 로컬·배포 DB에 적용할 테이블 생성 SQL.

| DB 컬럼 | 의미 | 타입 |
| --- | --- | --- |
| `execution_id` | 체결 ID | BIGINT |
| `order_id` | 체결된 주문 ID, `trade_orders` 외래키 | BIGINT |
| `execution_price` | 실제 사용한 체결가격 | BIGINT |
| `execution_quantity` | 체결수량 | BIGINT |
| `realized_pnl` | 매도 실현손익, 매수는 null | DECIMAL(19,2) |
| `realized_return_percent` | 매도 실현수익률, 매수는 null | DECIMAL(19,4) |
| `created_at` | BE가 기록한 체결시각 | DATETIME(6) |

`Execution`은 가격과 수량이 양수인지 검사한다. 매수 체결에는 실현손익을 넣지 못하게 하고, 도메인 수정 메서드를 제공하지 않는다. 관련 필드의 `updatable=false`는 JPA의 일반 UPDATE에서 해당 필드를 제외한다. DB 관리자의 직접 SQL 수정까지 차단하는 장치는 아니다.

### 참고 자료

- [사용자 제공 ERD](https://www.erdcloud.com/d/7B94NpRBrYYTZ4HKg): 체결 테이블과 주문 관계의 참고 자료. 실제 코드와 사용자 정책을 우선했다.
- API 필드명에 관한 사용자 요청과 단계 1의 정책 문서.

### 결과 및 확인

로컬 MySQL에 `executions`를 생성하고 테이블 정의를 확인했다. `ExecutionTests`에서는 저장 후 다시 읽어 주문 연결과 금액 반올림을 확인했다. 이 시점에는 웹소켓 자동 체결이 아직 연결되지 않았다.

## 단계 3. 조건 판단과 자산 변경 구현

### 당시 코드 상황

체결 이력을 저장할 수 있게 되었지만 주문 상태, 계좌 잔액, 보유종목을 함께 변경하는 처리가 없었다. 서로 다른 요청이 같은 계좌를 동시에 변경하면 잔액·수량 계산이 어긋날 수도 있었다.

### 결정과 근거

계좌를 먼저 잠근 뒤 주문을 다시 조회하고, 하나의 DB 트랜잭션 안에서 전체 체결을 수행한다. 주문 생성·취소에서도 사용하는 계좌 잠금 방식을 재사용했다.

**트랜잭션**은 여러 DB 변경을 하나의 작업으로 묶는 것이다. 도중에 예외가 발생하면 변경 전체를 되돌린다. **계좌 잠금**은 같은 계좌의 자산 변경이 동시에 진행되지 않도록 순서를 잡는 것이다.

### 구현 내용

핵심은 [OrderExecutionService.executeLimit()](../src/main/java/com/stock_spoon/river_be/order/OrderExecutionService.java)다.

1. 현재가가 양수인지 검사한다.
2. `AccountRepository.findLockedById()`로 계좌에 쓰기 잠금을 건다.
3. 계좌에 속한 주문을 조회한다.
4. 이미 체결·취소되었거나 지정가가 아니면 처리하지 않는다.
5. 이전 날짜 주문 또는 15:30 이후 주문은 취소한다. 09:00 이전에는 체결하지 않는다.
6. 매수·매도 가격 조건이 맞지 않으면 대기를 유지한다.
7. 금액을 계산하고 현금·보유종목을 변경한다.
8. 체결 이력을 저장하고 주문을 `executed`로 변경한다.
9. 메서드가 정상 종료되면 Spring이 트랜잭션을 커밋한다.

`executeLimit()`이 `false`를 반환하는 경우는 가격 불충족뿐 아니라 처리 대상 아님, 시간 제한, 만료 취소도 포함한다. `false`가 항상 “아무 상태도 바뀌지 않았다”는 뜻은 아니다.

관련 파일:

- [AccountRepository.java](../src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java): `PESSIMISTIC_WRITE` 계좌 잠금.
- [Account.java](../src/main/java/com/stock_spoon/river_be/account/entity/Account.java): `changeCash()`가 잔액을 변경하며 음수 잔액·숫자 범위 초과를 검사.
- [Holding.java](../src/main/java/com/stock_spoon/river_be/order/Holding.java): 매수 수량·원가 추가, 부분 매도 수량·원가 감소.
- [Order.java](../src/main/java/com/stock_spoon/river_be/order/Order.java): `execute()`가 상태와 예약 현금을 변경.
- [OrderService.java](../src/main/java/com/stock_spoon/river_be/order/OrderService.java): 만료 취소도 계좌 잠금 뒤 수행하도록 수정.

#### 매수 예시

잔액 1,000,000원인 계좌가 70,000원에 2주 매수를 예약한 경우:

- 주문 저장 시 `reserved_cash = 140,000`, 실제 `cash_balance`는 아직 1,000,000원.
- 69,000원에서 체결되면 실제 매수금액은 138,000원.
- `cash_balance = 862,000`, 보유수량 2주, 취득원가 138,000원.
- 주문이 체결되면서 `reserved_cash = 0`.
- 예약은 현금 차감과 다르므로, 140,000원을 다시 잔액에 더하는 처리는 하지 않는다.

#### 매도 예시

3주를 총 200,000원에 보유하고 있다가 1주를 70,000원에 매도하는 경우:

~~~text
매도분 취득원가 = 200,000 × 1 / 3 = 약 66,666.6667원
실현손익 = 70,000 - 매도분 취득원가 = 3,333.33원
실현수익률 = 실현손익 / 매도분 취득원가 × 100 = 5.0000%
남은 취득원가 = 133,333.33원
남은 수량 = 2주
~~~

매도 대금 70,000원은 현금에 더한다. 전량 매도이면 보유종목 행을 삭제한다. 부분 매도이면 남은 수량·원가를 갱신한다.

나눗셈의 중간 값은 소수점 16자리로 계산하고, 저장 시 금액 2자리·수익률 4자리에서 `HALF_UP` 반올림한다. 평균단가를 먼저 정수로 반올림하지 않는다. `Math.multiplyExact()`와 `Math.addExact()`는 숫자 범위를 넘으면 잘못된 값으로 진행하는 대신 예외를 발생시킨다.

### 참고 자료

- 단계 1에서 확정한 주문·체결 정책.
- 당시 코드의 기존 계좌 잠금·예약 구조. 별도의 외부 자료를 추가로 참고하지 않았다.

### 결과 및 확인

내부 지정가 체결 서비스를 구현했다. 이 단계의 코드는 아직 웹소켓 수신과 연결되지 않았고, 검증은 아래 단계 5에서 수행했다.

## 단계 4. 취득원가를 양수로 제한

### 당시 코드 상황

기존 `Holding`과 테이블 생성 SQL은 취득원가 0을 허용했다. 매도 수익률은 취득원가로 나누므로, 0을 허용하면 계산 기준을 추가로 정해야 한다.

### 결정과 근거

사용자와 확인하여 보유수량이 남아 있는 종목의 총 취득원가는 양수로 제한했다. 현재 v1은 양수 가격의 매수로 보유종목을 만들므로 이 기준을 적용했다. 무상으로 주식을 지급하는 기능 등을 구현한 것은 아니다.

### 구현 내용

- `Holding` 생성자는 소수점 2자리 반올림 후에도 취득원가가 양수인지 검사한다.
- 부분 매도 후 남은 원가가 반올림되어 0이 되면 수량·원가를 바꾸기 전에 예외를 발생시킨다.
- 체결 서비스는 매도 전에 기존 보유종목의 원가가 양수인지 다시 확인한다.
- [order_phase1.sql](../db/order_phase1.sql)의 신규 테이블 제약을 `total_cost > 0`으로 변경했다.
- [holding_positive_cost.sql](../db/holding_positive_cost.sql)은 기존 테이블의 CHECK 제약을 변경한다.

예를 들어 0.004원은 소수점 2자리로 저장하면 0.00원이므로 생성이 거절된다. 부분 매도 계산이 이 예외에 걸리면 체결 트랜잭션 전체가 롤백된다.

### 참고 자료

없음(현재 코드 분석과 취득원가 양수 제한에 관한 사용자 승인에 근거).

### 결과 및 확인

로컬 MySQL에서 `total_cost <= 0`인 행이 0개임을 확인한 뒤 변경 SQL을 실행했다. 실제 CHECK 제약이 `total_cost > 0`으로 바뀐 것도 확인했다. 기존 데이터를 자동으로 수정하거나 삭제하지 않았다.

`HoldingTests`에 0원·반올림 후 0원 거절과 부분 매도 거절 시 객체 값 유지 검증을 추가했다.

## 단계 5. 조회 오류 수정과 내부 체결 검증

### 당시 코드 상황

내부 체결·취득원가·주문 테스트를 실행한 결과 9개 중 8개가 통과하고, 이전 날짜 주문 취소 테스트가 실패했다.

실패한 조회는 `Order.accountId`를 찾았지만 엔티티에는 `account` 관계와 그 내부의 `id`가 있었다. DB의 `account_id` 컬럼 이름과 JPQL에서 쓰는 Java 엔티티 속성은 구분해야 한다.

### 결정과 근거

실패 로그의 `UnknownPathException`과 실제 엔티티 구조를 확인한 뒤, 다른 조회에서 사용하던 명시적 JPQL 방식을 적용했다. 사용자 승인 후 수정했다.

### 구현 내용

[OrderRepository.java](../src/main/java/com/stock_spoon/river_be/order/OrderRepository.java)의 `findAllByAccountIdAndStatus()`에 다음 조회를 명시했다.

~~~java
@Query("select o from Order o where o.account.id = :accountId and o.status = :status")
~~~

JPQL의 `o.account.id`는 “주문의 계좌 객체 안에 있는 ID”라는 뜻이다. DB 테이블 이름을 직접 사용하는 SQL과는 다르다.

### 참고 자료

없음(실제 실패 로그, `Order`·`Account` 엔티티, 기존 Repository 조회에 근거).

### 결과 및 확인

수정 후 같은 테스트 9개 모두 통과했다. 매수·부분 매도·전량 매도, 실현손익, 중복 체결 방지, 장 마감 취소와 취득원가 검증을 확인했다. 아직 실제 가격 수신에 따른 자동 체결 검증은 아니다.

## 단계 6. 주문 생성·웹소켓 현재가를 체결 서비스에 연결

### 당시 코드 상황

`KiwoomStockStream`은 가격을 메모리에 저장하고 있었다. 하지만 그 가격을 체결 서비스에 전달하지 않았고, 주문 생성 응답은 항상 빈 `executions`를 만들었다.

또한 웹소켓 메시지 파싱은 구독 상태를 관리하는 잠금 안에서 수행했다. 그 잠금을 잡은 채 DB 체결·구독 갱신을 호출하면 잠금 간 대기 위험이 생기므로 처리 경계를 분리할 필요가 있었다.

### 결정과 근거

사용자와 다음 동작을 확정했다.

- 주문 저장 직후 보관된 현재가로 먼저 판단한다.
- 가격이 없으면 REST로 현재가를 조회한다.
- REST 조회 실패 시 저장된 주문은 유지하고 웹소켓 가격을 기다린다.
- 특정 주문 체결 실패가 다른 주문을 막지 않는다.
- 현재 가격의 경과 시간에 따른 별도 유효기간 제한은 추가하지 않는다. 기존 연결·구독 유효성 검사는 유지한다.

### 구현 내용

#### 1. 주문 저장 뒤 최초 판단

[OrderController.create()](../src/main/java/com/stock_spoon/river_be/order/OrderController.java)는 기존 구독 확인·주문 저장 이후 `OrderExecutionListener.orderCreated(order)`를 호출한다.

[OrderExecutionListener.java](../src/main/java/com/stock_spoon/river_be/order/OrderExecutionListener.java)의 최초 판단 순서:

1. `stream.latest(stockCode)`로 보관 가격 확인.
2. 있으면 해당 가격 사용.
3. 없으면 `market.currentPrice(stockCode)`로 REST 조회.
4. REST 조회 중 웹소켓 가격이 도착했다면 그 가격 우선 사용.
5. 해당 주문 한 건에 `executeLimit()` 호출.
6. 구독 목록 갱신.

REST 결과로 웹소켓 가격 캐시를 덮어쓰지 않는다. REST 실패를 0원으로 바꾸거나 주문 취소로 바꾸지도 않는다. 가격을 못 받으면 가격 판단을 기다리되, 기존 장 마감·날짜 경과 취소 규칙은 계속 적용된다.

#### 2. REST 현재가 조회

[KiwoomMarketClient.currentPrice()](../src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java)는 기존 토큰·RestClient 설정을 재사용한다.

~~~text
POST https://api.kiwoom.com/api/dostk/stkinfo
api-id: ka10001
Authorization: Bearer <서버 내부 토큰>
Body: {"stk_cd":"005930"}
~~~

응답의 `return_code`가 0인지, 종목코드가 요청과 같은지, `cur_prc`가 양수 정수 가격으로 변환되는지 확인한다. 방향 표기 `+`·`-`는 절댓값으로 바꾸며, 누락·0·소수 가격·숫자 범위 초과 등은 오류로 처리한다.

REST 호출은 서버 설정상 연결 3초·읽기 5초 제한을 사용한다. 이는 전체 주문 요청 시간이 반드시 8초 이하라는 보장은 아니다. 최초 판단은 HTTP 주문 요청 안에서 수행하므로 REST를 기다리는 만큼 응답이 늦어질 수 있다.

#### 3. 웹소켓 가격 전달

[KiwoomConfig.java](../src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomConfig.java)가 스트림의 가격 콜백을 `ApplicationEventPublisher.publishEvent()`에 연결한다. 여기서 이벤트는 BE 프로세스 안의 알림이며 FE로 전송되는 메시지가 아니다.

[KiwoomStockStream.java](../src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomStockStream.java):

1. `Session.onText()`가 웹소켓 메시지를 받는다.
2. 분할 메시지를 합친 뒤 `accept()`가 파싱한다.
3. 정상 등록된 종목의 `REAL / 0B`이면 `StockPrice`를 만든다.
4. 종목별 최신 값을 `prices`에 저장한다.
5. 잠금 밖에서 가격 콜백을 호출한다.
6. 가격 처리 뒤 다음 웹소켓 메시지를 요청한다.

`StockPrice`에는 종목코드, 현재가, 전일대비, 등락률, 거래시각, BE 수신시각이 있다. `0D` 호가 수신은 기존처럼 별도로 보관하며 이번 지정가 체결 이벤트로 사용하지 않는다.

`OrderExecutionListener.onPrice()`는 `@EventListener`로 가격을 받고, 해당 종목의 `pending + limit` 주문만 조회한다. 주문 생성시각보다 이전에 받은 이벤트는 그 주문에 적용하지 않는다. 반면 주문 생성 직후의 `orderCreated()`에서는 합의한 대로 기존 보관 가격을 사용할 수 있다.

수신 순서대로 동기 처리한다. 별도 메시지 브로커나 무제한 비동기 작업을 추가하지 않았다. DB 처리가 오래 걸리면 후속 시세 처리도 늦어질 수 있으며, 거래시각 역순 메시지 제거 또는 허용 지연 시간 규칙은 추가하지 않았다.

#### 4. 실패 격리와 구독 갱신

`attempt()`는 주문마다 별도 Spring 서비스 프록시를 통해 `executeLimit()`을 호출한다. Listener 전체를 하나의 체결 트랜잭션으로 묶지 않았다.

~~~text
주문 A 체결 호출 → 예외 → A 트랜잭션 롤백 → 로그
주문 B 체결 호출 → 성공 → B 트랜잭션 커밋
~~~

웹소켓 연결은 DB 체결 실패 때문에 끊지 않는다. 오류가 난 주문은 다음 시세에서 다시 판단한다. 구독 목록 갱신에 실패하면 기존 수요를 보존하고 기존 5초 주기에 재시도한다.

처음에는 매 시세마다 `subscriptions.refresh()`를 호출했으나, 전체 계좌의 만료 정리·잠금을 반복하게 되어 수정했다. 현재는 가격 이벤트에서 실제 체결이 발생한 경우 구독을 즉시 갱신하고, 주기적인 만료 정리는 기존 스케줄러가 수행한다. 체결 검사 중 만료되어 취소된 주문만 있으면 구독 정리는 다음 주기에 반영될 수 있다.

#### 5. 생성 응답

`OrderExecutionListener.response()`가 주문과 체결 내역을 읽고 [OrderCreateResponse.java](../src/main/java/com/stock_spoon/river_be/order/OrderCreateResponse.java)가 JSON용 값으로 변환한다.

이제 생성 직후 체결되면 `order_status: "executed"`, `reserved_cash: 0`, 실제 `executions`를 반환한다. 아직 미체결이면 `pending`과 빈 체결 목록을 반환한다. 이 응답은 조회 시점의 상태이며 이후 웹소켓 체결로 변할 수 있다.

### 참고 자료

- [키움 공식 주식기본정보요청 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/종목정보/get_domestic_stock_info.py): `ka10001`, 요청 경로, `stk_cd`, `cur_prc` 확인.
- [키움 공식 API 가이드](https://openapi.kiwoom.com/m/guide/apiguide?dummyVal=0): `0B` 주식체결·`0D` 주식호가잔량 분류 확인.
- [키움 공식 REAL 메시지 처리 코드](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/realtime/decoders.py): `REAL`, 종목코드, 유형, FID 값 구조 확인.
- 사용자 확정 대화: 최초 보관 가격 판단, REST 보완, 실패 주문 유지와 다른 주문 계속 처리.

공식 자료에서 고정된 몇 초 간격 또는 모든 체결의 무지연 전달 보장을 확인한 것은 아니다. “주식체결”이므로 가격이 같아도 새 체결 데이터가 올 수 있으며, 새 수신이 없다는 사실만으로 가격이 잘못되었다고 단정하지 않는다.

### 결과 및 확인

주문 생성과 실제 가격 수신의 진입점을 내부 체결 서비스에 연결했다. 자동 테스트 결과는 단계 7, 실제 키움 연결 결과는 단계 8에 구분한다.

## 단계 7. 자동 테스트로 연결 흐름과 실패 격리 검증

### 당시 코드 상황

웹소켓 전달·REST 보완·응답 변경을 연결했으므로, 메서드 단독 계산뿐 아니라 Spring 이벤트와 트랜잭션 경계를 확인해야 했다.

### 결정과 근거

실제 증권 서버와 로컬 MySQL 데이터를 건드리지 않고, 가짜 웹소켓·HTTP 응답과 H2 테스트 DB를 사용했다. 운영 계산 서비스는 실제 Spring 프록시를 통해 호출하는 테스트도 추가하여 롤백을 확인했다.

### 구현 내용

| 테스트 클래스 | 확인한 내용 |
| --- | --- |
| `ExecutionTests` | 체결 저장·주문 연결·금액 반올림 |
| `HoldingTests` | 취득원가 양수 규칙과 거절 시 객체 유지 |
| `OrderExecutionServiceTests` | 매수·부분/전량 매도·실현손익·중복 방지·마감 취소 |
| `OrderExecutionListenerTests` | 보관 가격 우선, REST 보완/실패, 조회 중 웹소켓 가격 우선, 다른 주문 계속 처리 |
| `OrderExecutionEventTests` | 실제 Spring 이벤트 전달·주문별 롤백·다른 계좌 커밋·응답의 체결 이력 |
| `OrderServiceTests` | 예약·취소와 이전 날짜 주문 정리 |
| `OrderSubscriptionServiceTests` | 등록 확인·실패 시 미저장·마지막 수요 정리 |
| `OrderControllerTests` | 주문 HTTP 인증·입력·구독 실패 응답 |
| `KiwoomMarketClientTests` | REST 경로·헤더·본문·현재가 변환/오류 |
| `KiwoomStockStreamTests` | 웹소켓 파싱·동적 구독·잠금 밖 가격 전달·처리 실패 후 연결 유지 |

테스트 파일은 [order 테스트 디렉터리](../src/test/java/com/stock_spoon/river_be/order/)와 [kiwoom 테스트 디렉터리](../src/test/java/com/stock_spoon/river_be/market/kiwoom/)에 있다.

실패 격리 통합 테스트는 의도적으로 보유수량 합산을 오버플로시킨다. 현금 차감 이후 실패하게 하여 실제 잔액 차감이 롤백되는지 확인한다. 다른 계좌 주문은 정상 체결되고, 같은 가격 이벤트를 다시 보내도 체결이 중복되지 않는지도 확인한다.

### 참고 자료

없음(현재 코드의 거래 경계와 확정 정책을 검증 대상으로 삼음).

### 결과 및 확인

최종 실행 명령:

~~~powershell
.\gradlew.bat test --tests '*OrderExecution*Tests' --tests '*ExecutionTests' --tests '*HoldingTests' --tests '*OrderServiceTests' --tests '*OrderSubscriptionServiceTests' --tests '*OrderControllerTests' --tests '*KiwoomMarketClientTests' --tests '*KiwoomStockStreamTests'
~~~

- 45개 실행, 실패 0개, 건너뜀 0개. `BUILD SUCCESSFUL`.
- `--tests`는 실행할 테스트 이름을 선택한다. 이 명령은 전체 프로젝트 테스트·모든 Gradle 검사 실행을 의미하지 않는다.
- 실제 키움 장중 시세에 따른 자동 체결을 검증한 것은 아니다.
- 아래 실전 테스트 실행은 같은 Gradle 결과 폴더를 갱신하므로 현재 폴더에는 이 45개 결과가 그대로 남아 있지 않을 수 있다. 위 숫자는 당시 실행·XML 확인 결과다.

## 단계 8. 장외 실제 키움 연동 검증

### 당시 코드 상황

사용자가 장외에 가능한 실제 연결 검증만 승인했다. 장중 실제 가격 수신과 자동 체결은 해당 시점에 검증할 수 없으므로 주문 생성·DB 변경 없이 확인했다.

### 결정과 근거

기존 실전 테스트를 활용해 인증·구독 확인과 REST 조회만 실행했다. API 키·시크릿은 로컬 설정에서 읽었으며 토큰·인증값은 결과에 출력하지 않았다. 서버의 거래시간 제한을 풀지 않았다.

### 구현 내용

기존 `KiwoomTokenLiveTests`, `KiwoomStockStreamLiveTests`를 사용했다. `KiwoomMarketLiveTests`에는 `retrievesSamsungCurrentPrice()`를 추가하여 현재가 REST 조회를 확인했다.

검증 프로세스에만 `KIWOOM_LIVE_TEST=true`, `KIWOOM_STREAM_LIVE_TEST=true`를 설정하고, 실행 뒤 기존 환경변수 값으로 복원했다.

~~~powershell
.\gradlew.bat test --tests '*KiwoomTokenLiveTests' --tests '*KiwoomStockStreamLiveTests' --tests '*KiwoomMarketLiveTests.retrievesSamsungCurrentPrice'
~~~

위 명령만 실행하면 실전 활성화 환경변수가 없을 때 테스트가 건너뛰어질 수 있다. 활성화 여부와 결과의 `skipped`를 함께 확인해야 한다.

### 참고 자료

- 단계 6에서 확인한 키움 공식 현재가·웹소켓 자료.
- 기존 프로젝트의 명시적 활성화 방식 실전 테스트와 사용자 승인 범위.

### 결과 및 확인

2026-10-01 장외 검증에서 3개 실행, 실패 0개, 건너뜀 0개를 확인했다.

| 검증 | 관찰한 결과 | 이 결과로 알 수 없는 것 |
| --- | --- | --- |
| 토큰 발급·재사용 | 성공 | 향후 연결·가격 수신의 지속 성공 |
| 삼성전자 웹소켓 LOGIN·REG | 성공 | 실제 현재가 수신·모의 주문 체결 |
| 삼성전자 REST 현재가 | 269,500원 반환 | 정확한 구독 순간 가격·장중 실시간성 |

웹소켓 테스트의 `REAL 수신 여부`는 `false`였다. 이 테스트는 등록 성공 직후 캐시 여부를 확인하고 종료하므로, 장외 전체 시간에 어떤 메시지도 오지 않는다는 증명은 아니다.

실제 키움 주문 API는 호출하지 않았고 로컬 DB 변경도 수행하지 않았다. REST 가격을 받아 실제 모의 주문을 체결시킨 테스트도 아니다.

## 현재 코드 전체 흐름: 처음 읽는 개발자를 위한 안내

### 1. 어떤 클래스가 무엇을 담당하는가?

| 클래스 | 쉬운 설명 |
| --- | --- |
| `OrderController` | HTTP 주문 요청을 받고 인증·입력을 확인한다. |
| `OrderMarketValidator` | 주문 접수 시간, 상품 범위, 지정가 호가단위를 확인한다. |
| `OrderSubscriptionService` | 어떤 종목의 시세가 필요한지 관리하고 REG 성공을 기다린다. |
| `OrderService` | 주문을 저장하고 현금·수량 예약, 취소를 담당한다. |
| `KiwoomTokenProvider` | 키움 요청에 사용할 토큰을 발급·재사용한다. |
| `KiwoomMarketClient` | REST로 종목정보·현재가 등을 조회한다. |
| `KiwoomStockStream` | 키움 웹소켓에 연결해 현재가·호가를 받고 최신 값을 보관한다. |
| `KiwoomConfig` | 위 외부 연결 객체를 Spring에 등록하고 가격 알림을 연결한다. |
| `OrderExecutionListener` | 최초 가격과 이후 가격 이벤트를 체결 서비스로 전달한다. |
| `OrderExecutionService` | 잠금·트랜잭션 안에서 조건을 판단하고 자산을 반영한다. |
| `Order / Account / Holding / Execution` | DB에 저장되는 주문·계좌·보유종목·체결을 표현한다. |
| Repository들 | 엔티티를 저장·조회한다. JPQL은 엔티티 속성으로 작성한다. |
| `OrderCreateResponse` | 주문 상태와 체결 내역을 HTTP 응답 형식으로 바꾼다. |

### 2. 주문을 생성했을 때

~~~text
AI 서버: POST /api/v1/accounts/{accountId}/orders
  → Spring Security: JWT 쿠키·CSRF 확인
  → OrderController.create(): AI actor, 입력, 사용자·계좌 권한 확인
  → OrderMarketValidator.validateLimit(): 접수 시간·종목·호가단위 확인
  → OrderSubscriptionService.create(): 임시 수요 확보, REG 성공 대기
  → OrderService.reserveLimit(): 계좌 잠금, 현금/수량 확인, pending 저장·커밋
  → 임시 수요 해제: DB의 pending 주문이 구독 수요를 유지
  → OrderExecutionListener.orderCreated(): 보관 현재가 또는 REST 현재가
  → OrderExecutionService.executeLimit(): 조건에 맞으면 체결·커밋
  → 구독 갱신
  → OrderExecutionListener.response(): DB 상태·체결 조회
  → OrderCreateResponse: HTTP 201 응답
~~~

`HTTP 201`은 주문 생성 성공이다. 항상 체결 완료라는 뜻은 아니며 `order_status`를 확인해야 한다. 구독 등록 실패는 주문 저장 전 503, 초기 REST 조회 실패는 주문 저장 후 대기 유지로 처리한다.

또한 현재 HTTP 주문 생성은 AI 주문만 지원하며 지정가만 접수한다. 내부 체결 서비스가 존재한다고 일반 사용자 또는 시장가 주문 API까지 열린 것은 아니다.

### 3. 주문을 걸어 둔 뒤 새 가격이 왔을 때

~~~text
키움: REAL / 0B
  → KiwoomStockStream.Session.onText()
  → 가격 파싱·prices 최신 값 교체
  → 구독 잠금 해제
  → KiwoomConfig에 연결된 publishEvent(StockPrice)
  → OrderExecutionListener.onPrice()
  → 해당 종목의 pending 지정가 주문 조회
  → 주문마다 executeLimit()
      → 계좌 잠금 → 주문 최신 상태 확인 → 조건 판단
      → 현금/보유종목 변경 → Execution 저장 → Order.execute()
      → 커밋, 실패하면 롤백
  → 실제 체결이 있으면 OrderSubscriptionService.refresh()
~~~

한 종목에 여러 주문이 있으면 각각 판단한다. 같은 계좌에서는 잠금으로 자산 변경을 직렬화한다. 특정 주문이 실패해도 그 예외는 주문별 호출에서 처리하여 다음 주문으로 넘어간다.

### 4. 마지막 주문이 끝났을 때

`refresh()`는 만료 주문을 정리한 뒤 DB의 대기 주문 종목 집합과 접수 중 임시 수요를 합친다.

- 삼성전자 주문 A만 체결되고 주문 B가 남으면 삼성전자 구독 유지.
- 삼성전자 마지막 주문이 끝났지만 다른 종목 주문이 있으면 삼성전자만 `REMOVE`, 웹소켓 연결 유지.
- 모든 종목 수요가 사라지면 웹소켓 연결 종료.
- 구독 해제 또는 연결 종료 시 해당 가격 캐시도 제거.
- 정리 실패 시 즉시 해제를 보장하지 않고, 기존 5초 주기에 재시도.

현재 구독 수요는 주문 기준이다. 종목 상세페이지 방문자의 수요를 받는 기능은 이번 v1에 추가하지 않았다.

### 5. 장이 끝났을 때

별도 키움 장마감 신호를 사용하지 않고, 한국 시간과 기존 `@Scheduled(fixedDelay = 5000)`로 판단한다.

15:30 이후 `cancelExpiredPendingOrders()`가 대기 주문을 취소하고 예약 현금을 0으로 바꾼다. 구독 목록 갱신도 이어진다. 정확히 15:30:00에 모든 주문이 동시에 취소된다는 보장은 없고, 실행 주기·DB 처리 시간에 따라 반영된다. 서버가 꺼져 있었다면 재기동 후 이전 날짜 대기 주문을 정리한다.

체결 서비스 자체도 처리 시각이 15:30 이상이면 체결 대신 취소한다. 따라서 스케줄러보다 먼저 시세가 도착해도 마감 뒤 체결하지 않는다.

### 6. “실시간인데 보관한다”는 뜻

`prices`는 BE 메모리의 `Map<String, StockPrice>`다.

~~~text
005930 → 마지막 삼성전자 가격 한 건
000660 → 마지막 SK하이닉스 가격 한 건
~~~

새 가격이 오면 같은 종목의 기존 값을 교체한다. 가격 이력을 DB에 계속 저장하는 방식이 아니다. 재시작·연결 종료 후에는 새 수신 또는 최초 REST 조회가 필요하다.

`latest()`는 단순히 Map에 값이 있다고 돌려주지 않는다. 정상 세션·등록된 종목·해제 진행 여부·마지막 메시지 기준 연결 유효성을 확인한다. 기존 구현은 연결의 마지막 메시지가 90초 이상 오래되면 조회를 유효하게 취급하지 않는다. 이 90초는 개별 종목 가격의 나이를 제한하는 규칙과 다르다.

거래시각은 키움 데이터의 시각, 수신시각은 BE가 받은 시각, 체결시각은 BE가 체결을 처리한 시각이다. 서로 같다고 가정하지 않는다.

#### 즉시 체결된 생성 응답 예시

아래 값은 설명용 가상 값이다. 70,000원 지정가로 2주 매수를 요청했는데 최초 판단 가격이 69,000원이어서 바로 체결된 상황이다.

~~~json
{
  "message": "success",
  "order_id": 1001,
  "account_id": 123,
  "stock_code": "005930",
  "order_side": "buy",
  "order_type": "limit",
  "limit_price": 70000,
  "quantity": 2,
  "order_status": "executed",
  "reserved_cash": 0,
  "created_at": "2026-10-01T01:00:00Z",
  "executions": [
    {
      "execution_id": 2001,
      "execution_price": 69000,
      "execution_quantity": 2,
      "realized_pnl": null,
      "realized_return_percent": null,
      "created_at": "2026-10-01T01:00:01Z"
    }
  ]
}
~~~

현재 응답 DTO의 시간 타입은 `Instant`다. 예시의 `Z`는 UTC를 뜻하며, 한국 시간 표시로 바꾸면 주문은 10:00:00, 체결은 10:00:01이다. 매수이므로 실현손익은 null이다. 조건을 만족하지 않으면 `pending`, 예약금 140,000원, `executions: []`가 된다.

## DB 적용 안내

로컬에서는 `executions` 생성과 취득원가 CHECK 변경을 이미 적용했다. 다른 환경에 SQL이 자동 전파되는 것은 아니다.

- 새 DB: 기존 계좌 등 기본 테이블 → `order_phase1.sql` → `order_execution.sql` 순서로 적용한다.
- 기존 주문 DB: `executions`가 없다면 `order_execution.sql` 적용. 기존 취득원가 제약이 0을 허용한다면 데이터 확인 후 `holding_positive_cost.sql` 적용.
- 이미 적용된 DB에 CREATE TABLE을 다시 실행하지 않는다. SQL에는 `IF NOT EXISTS`가 없다.
- 취득원가가 0 이하인 기존 행이 있으면 CHECK 변경이 실패할 수 있다. 이 SQL은 데이터를 자동 수정하지 않는다.
- `ddl-auto=validate`는 필요한 테이블을 생성하거나 CHECK 변경을 대신 수행하지 않는다. DB 제약 적용 여부는 SQL로 따로 확인해야 한다.

## 아직 남은 검증·범위

- 실제 장중 가격 수신 → 조건 충족 → 자산 반영 → 마지막 구독 해제의 전체 흐름.
- 실제 키움 연결에서 여러 주문·종목의 구독 유지와 마감 정리.
- 시세 지연·거래시각 역순 수신에 대한 별도 정책과 처리량 증가 시 실행 방식.
- 휴장일·실제 장 상태·거래정지 판단은 현재 범위 밖.
- 시장가 주문과 호가 잔량에 따른 전량 체결은 미구현.
- 이번 브랜치 변경은 문서 작성 시점에 커밋·push·PR되지 않은 작업 트리다.