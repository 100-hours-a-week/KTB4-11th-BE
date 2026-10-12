# BE 인메모리 데이터 현황과 Redis 전환 방향

작성일: 2026-10-11

이 문서는 2026-10-11 당시 BE 소스에서 확인한 저장 방식과 V2 방향을 정리한 이전 구조 조사 기록이다. 이후 Redis 전환 구현과 검증을 진행했으며 최신 상태는 [redis-deployment-checklist.md](redis-deployment-checklist.md)를 참고한다. 아래 현재·기존 구조 설명은 조사 당시 기준이며 실행 중 메모리 분석이 아니라 애플리케이션이 직접 관리하는 데이터와 상태를 조사한 결과다.

## 1. 인메모리 저장이란

현재는 Spring이 관리하는 Java 객체의 필드에 데이터를 보관한다. `HashMap`, `HashSet`, `AtomicReference`, 일반 변수 등이 JVM 메모리에 존재하며 여러 요청에서 재사용된다.

- BE가 재시작되면 이 메모리 값은 사라진다.
- BE가 여러 대면 각각 별도의 값을 가진다.
- `synchronized`와 `AtomicReference`는 같은 JVM 안의 동시 접근을 보호한다. 다른 BE까지 함께 보호하지는 않는다.
- 요청 처리 중 잠깐 생성하는 지역변수도 메모리를 쓰지만, 여러 요청에서 재사용하는 캐시와는 수명이 다르다.

## 2. 도출한 항목

| 항목 | 현재 저장 방식 | 보관 내용 | V2 처리 방향 |
|---|---|---|---|
| 종목별 최신 현재가 | `HashMap<String, StockPrice>` | 현재가, 전일 대비, 등락률, 체결 시각, 수신 시각 | Redis 공유 저장 |
| 종목별 최신 호가 | `HashMap<String, OrderBook>` | 매도·매수 각 10단계 가격·잔량, 호가 시각, 수신 시각 | Redis 공유 저장 |
| 코스피 지수 | `AtomicReference<KospiIndex>` | 최신 지수, 전일 대비, 등락률, 조회 시각 | Redis 공유 저장 검토 |
| 코스피 조회 시도 시각 | `volatile Instant lastAttempt` | 마지막 조회 시도 시간 | 조회 담당의 작업 상태로 관리 |
| 키움 인증 토큰 | `String token`, `Instant expiresAt` | 토큰과 만료 시각 | 공유·재발급 정책 결정 필요 |
| 접수 중 주문의 구독 수요 | `HashMap<String, Integer> accepting` | 종목별 접수 처리 중인 요청 수 | 전체 BE의 수요를 수집 담당에게 전달 |
| WebSocket 구독 관리 | `HashSet`, `HashMap` | 필요 종목, 실제 등록 완료 종목, 등록 대기 작업 | 전체 수요는 공유, 실제 연결의 등록 상태는 담당 BE에서 관리 |
| WebSocket 연결 관리 | `Session`, 상태 변수, 버퍼 등 | 실제 연결, 인증 여부, 마지막 수신 시각 등 | 담당 BE 메모리 유지 |
| REST 호출 간격 | `long nextQueryAt` | 다음 조회 가능 시점 | 담당 BE에서 TR별로 관리하도록 변경 |
| 요청 내부 임시 맵·응답 계산값 | 메서드 지역변수와 응답 객체 | 가격 재사용, 집계, 가용 현금 등 | 기존처럼 요청 처리 중 사용 |

## 3. 현재가와 호가

`KiwoomStockStream`은 하나의 WebSocket 연결에서 여러 종목의 주식체결(`0B`)과 주식호가잔량(`0D`)을 구독한다.

```java
private final Map<String, StockPrice> prices = new HashMap<>();
private final Map<String, OrderBook> books = new HashMap<>();
```

종목코드가 키다. 유효한 메시지를 받으면 `put()`으로 해당 종목의 기존 값을 교체한다. 종목별 최신 값만 보관하며 이력을 쌓지 않는다. 현재가가 직전 값과 같아도 새 메시지를 받으면 객체와 수신 시각을 갱신한다.

조회 시 구독과 연결이 유효한지 확인한 뒤 값을 반환한다. 구독 수요가 사라진 종목은 캐시에서 제거하며, 연결 종료 시 현재가·호가 캐시를 비운다. 읽기·쓰기는 스트림 객체의 잠금으로 보호한다.

Redis 전환 시 값뿐 아니라 수신 시각과 연결 유효성을 함께 고려해야 한다. 연결 종료 후 남은 값을 최신 시세로 취급하지 않도록 정책이 필요하다.

## 4. 코스피 지수와 키움 토큰

### 코스피 지수

`KospiIndexService.latest`는 마지막 조회에 성공한 코스피 정보 한 묶음을 보관한다. 여러 사용자 요청이 이 값을 읽는다. `AtomicReference`는 정보 묶음을 안전하게 교체하기 위한 Java 도구다.

기본적으로 스케줄 작업 종료 후 10초 간격으로 실행하며, 한국 시간 월~금 09:00 이상 15:40 미만에 REST 조회를 시도한다. 별도 휴장일 판정은 없다. 해당 시간 밖에서도 초기값이 없으면 최소 60초 간격으로 조회를 시도한다. `lastAttempt`가 마지막 시도 시각을 기억한다.

조회 실패 시 이전 성공 값을 유지하며 자동 만료 처리는 없다. Redis로 옮길 때도 갱신 주기와 오래된 값의 반환 정책을 정해야 한다. `AtomicReference` 자체가 아니라 그 안의 데이터를 저장한다.

### 키움 인증 토큰

`KiwoomTokenProvider`는 키움 REST 요청과 WebSocket 로그인용 토큰을 저장한다. 사용자 로그인 JWT와는 별개다.

토큰이 필요할 때 `accessToken()`을 호출한다. 만료까지 60초보다 많이 남아 있으면 재사용하고, 그렇지 않으면 발급을 시도한다. 별도 주기적 갱신 스케줄러는 없다. 키움이 거절한 토큰이 현재 보관 중인 토큰과 같을 때만 폐기한다.

발급·폐기는 `synchronized`로 보호한다. Redis 공유 시에는 만료뿐 아니라 동시 발급과 오래된 요청에 의한 새 토큰 삭제를 방지해야 한다. 키움 호출을 한 담당 BE로 모으므로 공유 저장 필요성과 담당 전환 시 재발급 정책은 별도로 결정한다.

## 5. 접수 중 주문의 구독 수요

`OrderSubscriptionService.accepting`은 DB에 아직 저장되지 않은 주문 때문에 시세가 필요하다는 임시 표시다.

```text
005930 → 2  : 삼성전자 주문 요청 2건을 접수 처리 중
```

주식 수량이나 DB의 전체 대기 주문 수가 아니다. 현재 주문 접수 순서는 다음과 같다.

1. 종목의 접수 중 개수를 증가시킨다.
2. 필요한 시세의 구독 완료를 기다린다. 기본 제한은 10초다.
3. 구독이 성공하면 주문 저장 작업을 수행한다.
4. 성공·실패와 관계없이 접수 중 개수를 줄이고, 0이면 제거한다.

```text
구독이 필요한 종목 = DB의 PENDING 주문 종목 ∪ 접수 중 주문 종목
```

DB 저장 전에는 임시 표시가 구독을 유지하고, 저장 후 PENDING 상태라면 DB 주문이 구독을 유지한다. 서버 시작 시와 5초 주기로 DB 수요를 반영한다.

여러 BE에서는 각 서버의 접수 중 수요를 수집 담당이 알 수 있어야 한다. 특정 BE가 종료됐을 때 남은 임시 수요를 정리하는 정책도 필요하다.

## 6. WebSocket 연결과 구독 상태

WebSocket은 연결을 유지하면서 키움이 보내는 새 데이터를 계속 받는 통신 방식이다. 현재는 종목별 연결이 아니라 연결 하나에서 여러 종목을 구독한다.

```text
키움과 WebSocket 연결 1개
 ├─ 삼성전자: 등록 완료
 ├─ 카카오: 등록 완료
 └─ 네이버: 등록 응답 대기
```

구독 수요와 실제 구독 상태의 역할은 다르다.

| 값 | 역할 |
|---|---|
| `accepting` | 주문 접수 때문에 해당 종목이 필요한 이유를 기록 |
| `symbols` | 전체 수요를 반영한 구독 대상 목록 |
| `registered` | 현재 연결에서 키움이 등록 성공을 응답한 목록 |
| `registrations` | 등록 완료를 기다리는 `CompletableFuture` 작업 |

필요한데 등록되지 않은 종목은 등록하고, 더 이상 필요하지 않은 종목은 해제한다. 등록 성공은 첫 시세 수신을 의미하지 않는다.

연결 자체의 상태도 메모리에 기억한다.

| 값 | 쉬운 설명 |
|---|---|
| `state`, `authenticated` | 연결·로그인·구독이 어디까지 진행됐는지 |
| `socket` | 실제 통신 연결 |
| `startedAt`, `lastMessage` | 연결 시작 시각과 마지막 메시지 수신 시각 |
| `control`, `controlSymbols`, `controlStartedAt` | 응답을 기다리는 등록·해제 요청과 대상·시각 |
| `fragments` | 여러 조각으로 도착한 메시지를 합치는 버퍼 |
| `sends` | 메시지 전송 순서를 유지하는 작업 |
| 세션의 `token` | 로그인에 사용할 임시 토큰. 인증 성공 후 비움 |

5초 간격 점검에서 인증·등록/해제 응답이 10초 내 완료되지 않거나, 90초 동안 메시지가 없으면 연결을 폐기한다. 마지막 메시지는 PING 등도 포함하므로 연결 생존과 특정 종목 시세의 최신성은 다르다.

실제 연결·버퍼·대기 작업은 수집 담당 BE 메모리에 유지한다. Redis에는 담당자와 공유 수요 등 필요한 정보만 둔다. 담당 A가 종료되면 B는 새 연결을 만들고 로그인·재구독한다. A의 연결 객체를 가져오는 것이 아니다.

## 7. REST 호출 간격: 현재 구현과 변경 기준

### 현재 구현

`KiwoomMarketClient`는 `long nextQueryAt` 하나와 `synchronized queryOnce()`로 이 클라이언트를 통과하는 REST 조회를 직렬 처리한다. 성공·실패한 요청의 종료 후 220ms를 두고 다음 요청을 허용한다.

현재 구현은 `api-id`를 구분하지 않는 통합 제한이다. WebSocket 수신 간격이나 별도 토큰 발급 요청을 제어하는 값은 아니다. `System.nanoTime()`으로 측정한 프로세스 내부 시간 기준이므로 숫자를 그대로 다른 서버에 전달해 비교하면 안 된다.

### 사용자 확인에 따른 V2 설계 기준

**호출 한도는 TR(`api-id`)별로 별도 카운트한다.** 예를 들어 각 TR의 초당 한도가 5회이고 추가 합산 제한이 없다면, `ka10001` 4회와 `ka10100` 4회를 같은 1초 동안 호출하는 것은 각각의 한도 안에 해당한다.

따라서 현재의 공통 시간 변수 하나를 TR별 호출 제한 관리로 변경한다. 이는 코드의 현재 동작과 구분되는 변경 요구사항이다.

이 TR별 기준은 사용자가 제공한 설명을 반영한 설계 전제다. 공식 소개 페이지의 “조회 TR 초당 5회” 문구만으로 전체 조회 합산 제한이라고 단정했던 이전 설명은 채택하지 않는다. 운영 적용 전에 실제 계정·앱·토큰 범위와 실전/모의 환경, TR별 수치 및 별도 그룹·전체 제한을 확인한다. 별도 제한이 있으면 TR별 제한과 함께 적용한다.

공식 확인 위치: [키움 REST API 사용 안내](https://openapi.kiwoom.com/intro?dummyVal=0)

### Redis 적용 방향

WebSocket뿐 아니라 **키움 REST 호출도 담당 BE 한 대로 모으기로 했다.** 따라서 TR별 호출 제한은 담당 BE 메모리에서 관리할 수 있고, 카운터의 Redis 저장은 필수가 아니다.

담당 전환 시 이전 담당의 직전 호출량과 겹치지 않도록 인계 대기 등 정책이 필요하다. 기존 담당과 새 담당이 동시에 키움 호출을 하지 않도록 소유권 관리도 필요하다. 추후 여러 BE의 직접 REST 호출을 허용한다면 공유 제한을 다시 설계해야 한다.

## 8. 요청 내부 임시 맵과 계산 응답

`AccountService.evaluate()`의 종목별 가격 맵은 한 요청에서 같은 종목 가격을 반복 조회하지 않기 위한 메모다. 계좌 A와 B가 모두 삼성전자를 보유하면 한 번 구한 가격을 해당 요청 안에서 재사용한다.

`OrderHistoryService`의 종목명 맵, `OrderBuyPriceService`의 가격 맵, AI 사용자 응답을 조립하는 맵 등도 메서드 내부 임시 데이터다. 다음 요청은 새 맵을 만들며, 기존 객체는 참조가 없어지면 가비지 컬렉션 대상이 된다. 이 맵들은 Redis로 옮길 필요 없이 유지한다.

가용 현금도 지속 캐시가 아니다.

```text
가용 현금 = DB의 계좌 현금 잔고 − DB의 PENDING 주문 예약 현금 합계
```

요청 시 계산해 응답 객체에 넣는다. 총자산·수익률도 요청 시 계산한다. 계산값과 응답 객체는 잠시 메모리에 존재하지만 다음 요청을 위해 저장하지 않는다. 사용자 Refresh Token의 해시 역시 서버 메모리 캐시가 아니라 DB에 저장된다.

## 9. V2의 전체 방향과 남은 결정

```text
일반 BE들 → Redis에서 공유 데이터 조회
                 ↓ 값 확보가 필요함
          수집 담당 BE에 요청 전달
                 ↓
      키움 WebSocket 구독 / REST 조회
                 ↓
          Redis에 결과 저장·알림
```

Redis 데이터 형식, 만료·최신성 기준, 요청 전달 방식, 담당 선출·복구, 구독 수요 정리, Redis 장애 시 동작은 후속 설계에서 확정한다. 여러 BE가 같은 시세로 같은 주문을 중복 체결하지 않도록 하는 처리도 포함한다. Pub/Sub 알림만으로 모든 체결 이벤트의 재전달을 보장한다고 가정하지 않는다.

## 10. 확인한 주요 소스

- `river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomStockStream.java`
- `river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomTokenProvider.java`
- `river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java`
- `river_be/src/main/java/com/stock_spoon/river_be/market/KospiIndexService.java`
- `river_be/src/main/java/com/stock_spoon/river_be/order/OrderSubscriptionService.java`
- `river_be/src/main/java/com/stock_spoon/river_be/order/OrderService.java`
- `river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountService.java`
- `river_be/src/main/java/com/stock_spoon/river_be/account/dto/AccountDetailResponse.java`
