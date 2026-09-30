# 주문 생성 종목 WebSocket 구독과 장 마감 취소 (v1)

> 이 문서는 구독·취소 구현 당시의 기록입니다. 이후 추가한 지정가 자동 체결, 최초 REST 가격 판단과 현재 코드의 전체 흐름은 [지정가 주문 자동 체결](stockspoon_order_execution.md)을 참고하세요.

## 작업 개요

- 목표: 대기 주문이 존재하는 종목의 현재가·호가 WebSocket 구독을 자동으로 관리하고, 장 마감 미체결 주문을 다음 거래일로 넘기지 않는다.
- 범위: 지정가 주문 접수 전 구독 확인, 종목별 구독 유지·해제, 유휴 연결 종료, 재기동 시 구독 복구와 만료 주문 정리.
- 기준 코드: `fix/34-order-create-websocket`, 작성 당시 HEAD `413f226`. 이 문서는 해당 HEAD 위에 존재하는 미커밋 작업 트리를 기록한다.
- 변경일: 2026-09-30.
- 작성일: 2026-10-01.

현재 구현은 키움으로 실제 주문을 전송하지 않으며, 웹소켓 현재가를 주문 가격과 비교해 체결시키지도 않는다. 주문 생성·대기 주문의 구독 생명주기와 장 마감 취소까지만 다룬다.

## 진행 순서

1. 고정 종목 목록을 런타임 대기 주문 기반 동적 구독으로 전환
2. 주문 저장 전에 WebSocket 구독 성공을 확인
3. 여러 주문의 구독 공유, 재기동 복구, 수요가 없을 때 연결 종료
4. 장 마감 미체결 주문 취소와 재기동 시 만료분 정리
5. 자동 테스트로 주문·구독 흐름 확인

## 단계 1. 고정 종목 구독을 동적 종목 구독으로 전환

### 당시 코드 상황

- 변경 전 `KiwoomStockStream`은 설정에서 받은 종목 목록을 연결 시 구독하고, 실행 중 주문이 생기거나 없어지는 것을 반영하지 않았다.
- 주문이 새 종목에 생성되어도 그 종목이 고정 목록에 없으면 주문 대기 중 시세를 받을 수 없었다.
- 관련 구현은 `src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomStockStream.java`와 `market/kiwoom/KiwoomConfig.java`다.

### 결정과 근거

- 선택: BE가 현재 필요한 종목 집합을 WebSocket에 전달하고, 연결 내부에서 등록된 종목과 원하는 종목의 차이를 계산해 `REG` 또는 `REMOVE`를 보낸다.
- 근거: 사용자가 정한 v1 흐름에서 대기 주문이 있는 종목은 시세가 필요하며, 주문이 모두 끝난 종목은 시세가 필요하지 않다. 종목 상세 화면의 방문 여부는 v1 주문 감시 수요가 아니다.
- 키움 제어 응답에 요청 ID가 없으므로 `REG`와 `REMOVE`를 한 번에 하나씩 전송하고 응답을 확인한다. 등록 응답을 받기 전까지 해당 종목을 구독 성공으로 취급하지 않는다.

### 구현 내용

- `KiwoomStockStream.updateSymbols(Set<String>)`가 원하는 종목 집합을 갱신한다. 종목코드 형식을 확인하고, 제거된 종목의 가격·호가 캐시를 폐기한다.
- `synchronizeSubscriptions()`는 현재 `registered` 집합과 원하는 `symbols` 집합의 차이를 계산해 등록·해제를 순차 전송한다.
- `whenSubscribed()`는 해당 종목의 `REG` 성공 응답을 기다리는 Future를 반환한다. 등록 거부나 연결 종료 시 대기 Future를 실패시킨다.
- 가격·호가 조회는 현재 세션에서 해당 종목이 등록된 경우에만 값을 돌려준다.
- 관련 코드: `market/kiwoom/KiwoomStockStream.java`의 `updateSymbols`, `whenSubscribed`, `synchronizeSubscriptions`, `latest`, `latestOrderBook`.

### 참고 자료

- [키움 REST API 가이드](https://openapi.kiwoom.com/guide/apiguide)와 [키움 공식 0B 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/실시간시세/subscribe_domestic_stock_trade_async.py): 실시간 현재가 등록 메시지와 데이터 유형 확인에 사용했다. 종목별 수요를 정하는 방식은 키움 문서가 아니라 프로젝트의 사용자 확정 정책이다.
- 사용자 확정 정책(2026-09-30 대화): 주문이 있는 종목은 수요가 유지되는 동안 감시하고, 동일 종목의 마지막 주문이 끝났을 때만 종목 구독을 해제한다.

### 결과 및 확인

- WebSocket은 주문 종목 집합을 동적으로 등록·해제할 수 있게 됐다.
- `KiwoomStockStreamTests`는 가짜 WebSocket으로 동적 등록 확인, 다른 종목 구독 보존, 재등록·늦은 응답 처리를 검증한다.
- 연결 등록 성공은 실제 현재가 수신이나 시장 운영을 보장하지 않는다. 장중 실시간 수신은 이 테스트로 검증하지 않았다.

## 단계 2. 주문을 저장하기 전에 구독 성공 확인

### 당시 코드 상황

- 변경 전 `OrderController.create()`는 시장·가격 검증 뒤 곧바로 `OrderService.reserveLimit()`을 호출해 주문을 저장했다.
- 따라서 동적 구독 실패가 주문 저장보다 먼저 확인되지 않았다.

### 결정과 근거

- 선택: 주문 API가 임시 구독 수요를 먼저 확보하고 `REG` 성공을 기다린 뒤 기존 주문 저장 로직을 호출한다.
- 구독이 실패하거나 10초 안에 성공을 확인하지 못하면 `503 MARKET_STREAM_UNAVAILABLE`을 반환한다. 확인되지 않은 시세 감시 상태로 주문을 접수하지 않도록 하기 위해서다.
- 구독 성공 후 잔액·수량 검증 또는 DB 저장이 실패하면 임시 구독 수요를 해제한다. DB에 이미 대기 주문이 남아 있으면 해당 주문이 구독 수요를 계속 소유한다.

### 구현 내용

- `OrderController.create()`는 시장 검증 뒤 `OrderSubscriptionService.create()`를 호출하고, 주문 예약은 그 콜백 안에서 수행한다.
- `OrderSubscriptionService.create()`는 종목을 임시 수요로 추가하고 최대 10초 동안 `whenSubscribed()`를 기다린다. 성공 후 주문 저장을 실행하고, 성공·실패 모두 `finally`에서 임시 수요를 해제한다.
- 구독 실패 시 주문 저장이 호출되지 않는다. 응답 오류는 `OrderException`의 503 경로를 사용한다.
- 관련 코드: `order/OrderController.java`, `order/OrderSubscriptionService.java`, `order/OrderService.java`.

### 참고 자료

- 참고 자료: 없음(코드 분석과 사용자 확정 요구사항에 근거). 구독 성공 전 주문을 저장하지 않는 순서는 프로젝트의 오류 처리 정책이며, 키움 API 문서에서 정한 사항이 아니다.

### 결과 및 확인

- `OrderControllerTests.subscriptionFailureReturns503WithoutAnOrderOrReservation`이 실패 응답과 주문·예약 미생성을 확인한다.
- `OrderSubscriptionServiceTests.waitsForRegistrationBeforeSavingAndRetainsCommittedPendingOrder`는 등록 성공 전 저장되지 않고, 저장된 대기 주문이 구독 수요를 이어받는 것을 확인한다.
- 테스트상 지정가 주문 접수는 여전히 BE 내부 모의 주문이다. 키움 주문 API는 호출하지 않는다.

## 단계 3. 대기 주문을 구독 소유자로 삼고 유휴 연결을 종료

### 당시 코드 상황

- 등록 API만 연결하면 주문 생성 요청이 끝난 뒤 임시 수요가 사라질 수 있었다.
- BE가 재기동되면 메모리 구독 상태가 없어지므로 DB에 남은 대기 주문에서 다시 구독 종목을 계산해야 했다.
- 사용자 합의는 단일 BE 인스턴스를 전제로 했다. 같은 종목을 보는 여러 사용자·주문은 하나의 종목 구독을 공유한다.

### 결정과 근거

- 선택: 원하는 종목 집합을 DB의 전체 `PENDING` 주문 종목과 현재 접수 중인 종목의 합집합으로 계산한다.
- 한 종목에 대기 주문이 여러 건이면 일부 주문이 취소되어도 마지막 대기 주문이 사라질 때까지 종목 구독을 유지한다.
- 수요 종목이 하나도 없으면 키움 세션을 정상 종료한다. 이 동작은 v1에 대해 사용자가 선택한 정책이다. 다음 주문 수요가 생기면 연결과 등록을 다시 시작한다.

### 구현 내용

- `OrderSubscriptionService.synchronizeSymbols()`는 `OrderRepository.findStockCodesByStatus(PENDING)`와 임시 `accepting` 종목을 합쳐 `stream.updateSymbols()`에 전달한다.
- 애플리케이션 준비 완료 시 `restorePendingSubscriptions()`를 호출하고, 이후 5초 주기로 DB와 구독 상태를 다시 맞춘다.
- `KiwoomStockStream.updateSymbols()`가 빈 집합을 받으면 `closeWhenIdle()`로 세션을 정상 종료하고 등록 상태·현재가·호가 캐시를 비운다.
- 기존 `KIWOOM_STREAM_SYMBOLS`의 정적 운영 구독은 제거했다. `KIWOOM_STREAM_ENABLED` 설정은 연결 기능의 활성화 여부에 사용한다.
- v1은 BE 한 인스턴스 가정이다. 여러 BE 인스턴스에서 구독 상태와 시세를 공유하는 처리는 이 변경 범위에 없다.

### 참고 자료

- 사용자 확정 정책(2026-09-30 대화): 마지막 대기 주문이 끝나면 그 종목만 해제하고, 다른 구독 종목이 남아 있으면 연결을 유지한다. 모든 종목의 수요가 사라지면 v1에서는 연결을 종료한다.
- 관련 기존 설명: `docs/stockspoon_kiwoom_websocket.md`의 설정·구독 절.

### 결과 및 확인

- 동일 종목의 수요는 공유되고, 다른 종목의 수요는 해당 종목 해제와 독립적으로 유지된다.
- `KiwoomStockStreamTests.dynamicRegistrationWaitsForAckAndRemovalKeepsOtherSymbolAndSocket`와 `OrderSubscriptionServiceTests.refreshRestoresPendingSymbolsAndRemovesOnlyAfterLastOrderDisappears`가 동적 구독 흐름을 확인한다.
- WebSocket은 서버 메모리에만 구독 상태를 보관한다. 멀티 인스턴스 운영은 검증하지 않았다.

## 단계 4. 장 마감 미체결 주문 취소와 재기동 보정

### 당시 코드 상황

- 기존 주문에는 사용자가 부르는 내부 `OrderService.cancel()`만 있었고, 장 마감 자동 취소는 없었다.
- 대기 주문은 DB에 `PENDING`으로 남으며, 구독 복구가 그대로 이루어지면 다음 날에도 해당 종목이 재구독될 수 있었다.

### 결정과 근거

- 확정 정책: 한국 시간 15:30 이후 미체결 주문을 취소하고 다음 거래일로 이월하지 않는다.
- 별도의 키움 장 종료 메시지 대신 BE 시계를 기준으로 처리한다. 웹소켓 단절은 장애일 수도 있어 장 종료와 구분할 수 없다는 점을 대화에서 확인했다.
- 장 마감 시 서버가 꺼져 있을 가능성에 대비해, 재기동 시 이전 한국 날짜의 대기 주문도 먼저 취소한 뒤 구독을 복구한다.

### 구현 내용

- `OrderService.cancelExpiredPendingOrders()`는 현재 시각을 `Asia/Seoul`로 바꿔 확인한다. 15:30 이상이면 모든 `PENDING` 주문을, 당일보다 생성 날짜가 이전인 주문은 시각과 관계없이 취소한다.
- `OrderSubscriptionService.refresh()`는 5초 주기로 만료 처리를 호출한 다음 DB의 `PENDING` 종목 목록으로 구독을 조정한다. 같은 메서드는 애플리케이션 준비 완료 시에도 호출되므로 재기동 때 만료 주문이 WebSocket 복구보다 먼저 정리된다.
- 취소는 `Order.cancel(now)`를 사용한다. 주문 상태는 `CANCELLED`, `cancelled_at`은 취소 시각, 매수 `reserved_cash`는 0이 된다. 매도 예약 수량은 `PENDING` 매도 주문을 합산하는 계산에서 제외되어 풀린다. 현금 잔액과 보유 수량은 주문 접수 때 바꾸지 않았으므로 변경하지 않는다.
- 취소 후 대기 주문이 더 없는 종목은 구독 집합에서 제거된다. 전체 수요가 0이면 연결도 종료된다.

### 참고 자료

- 사용자 확정 정책(2026-09-30 대화): 장 마감 시 취소, 다음 거래일로 유지하지 않음. 구현 시각은 현재 주문 생성 검증과 동일하게 한국 시간 15:30으로 했다.
- 참고 자료: 없음(코드 분석과 사용자 확정 정책에 근거). 거래소 장 운영 달력이나 장 종료 API는 이번 구현에서 사용하지 않았다.

### 결과 및 확인

- `OrderServiceTests.previousDayPendingOrdersAreCancelledAndReservedCashIsReleased`가 이전 날짜 주문의 취소, 예약금 반환, 취소 시각 기록을 검증한다.
- `OrderSubscriptionServiceTests.refreshRestoresPendingSymbolsAndRemovesOnlyAfterLastOrderDisappears`는 갱신 때 만료 처리 경로가 실행되는 것을 검증한다.
- 5초 주기 확인이므로 정확히 15:30:00에 실행된다고 보장하지 않으며, 다음 갱신 시점에 취소된다. 서버가 꺼져 있다면 재기동 후 첫 구독 복구 과정에서 정리된다.
- 휴장일·임시 개장 지연·조기 마감 달력은 반영하지 않는다. 주문 생성 검증도 요일·휴일을 확인하지 않아, 현재 구현은 실제 거래일을 판별하는 기능이 아니다.

## 단계 5. 검증 결과와 남은 범위

### 수행한 확인

- 다음 Gradle 명령으로 주문 예약·취소와 주문 구독 관련 테스트를 실행했다.

```powershell
.\gradlew.bat test --tests com.stock_spoon.river_be.order.OrderServiceTests --tests com.stock_spoon.river_be.order.OrderSubscriptionServiceTests
```

- 결과: `BUILD SUCCESSFUL`. Java 본 코드와 테스트 코드 컴파일이 완료되고 지정한 테스트가 통과했다.
- `git diff --check`도 통과했다.
- 실전 키움 연결·실시간 장중 데이터는 이 테스트에서 확인하지 않았다. 동적 등록·해제의 관련 테스트는 가짜 WebSocket을 사용한다.

### 현재 미구현 또는 제한

- 현재가를 주문 조건과 비교하는 로직, 자동 체결, `EXECUTED` 전환, 체결 내역·현금·보유 종목 갱신은 없다. 대기 주문은 가격이 지정가를 통과해도 자동 체결되지 않는다.
- 시장가 주문, HTTP 주문 취소 API, 사용자별 구독 소유권, 멀티 인스턴스 구독 공유는 이 문서의 범위가 아니다.
- 고정 15:30 기준은 사용자 확정 v1 정책을 구현한 것이다. 한국거래소 휴장일과 특수 장 시간에 맞춰 자동 보정하지 않는다.

## 관련 문서와 코드

- API 계약·접수 검증: `docs/stockspoon_order_create.md`
- Kiwoom WebSocket 프로토콜·설정: `docs/stockspoon_kiwoom_websocket.md`
- 기존 주문 정책 흐름: `docs/stockspoon_order_create_flow.md`
- 구독 조정: `src/main/java/com/stock_spoon/river_be/order/OrderSubscriptionService.java`
- 만료 취소·예약 해제: `src/main/java/com/stock_spoon/river_be/order/OrderService.java`, `Order.java`, `OrderRepository.java`
- Kiwoom 연결·등록·해제: `src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomStockStream.java`
