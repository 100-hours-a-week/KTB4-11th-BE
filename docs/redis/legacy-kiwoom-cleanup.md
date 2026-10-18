# 기존 키움 연동 코드 정리 내역과 남은 작업

작성일: 2026-10-17  
작업 브랜치: `refactor/147-cleanup-legacy-kiwoom`

이 문서는 해당 브랜치의 로컬 수정 내용을 설명한다. 실제 dev 서버에 배포되었다는 의미는 아니다. 이번 범위는 **현재 호출하지 않는 코드를 제거하는 것**이며, 시세 수신 구조나 주문 정책을 새로 변경하는 작업은 아니다.

## 후속 변경: 평가용 현재가의 Redis 전환 (2026-10-18)

아래 내용은 최초 정리 시점의 기록이다. 이후 `AccountService`(계좌 목록·상세 및 이를 사용하는 대회 조회), `AccountHoldingsService`, `OrderBuyPriceService`의 현재가 조회를 `RedisMarketPrice`로 전환했다. 이 호출자들은 키움 `currentPrice`를 더 이상 사용하지 않는다. 종목정보와 날짜별 종가는 Redis 규격 미확인으로 기존 REST를 유지한다. 직접 조회의 검증·장외 사용·실패 정책은 [AI 시세 연동](ai-quotes.md)의 ‘평가용 현재가 직접 조회’를 참고한다.

## 1. 먼저 구분해야 할 두 가지 구독

| 구분 | 하는 일 | 대상 |
|---|---|---|
| 시세 서버의 키움 구독 | 키움에서 실시간 시세를 수집해 Redis에 전달 | 팀에서 합의한 코스피200 종목 |
| BE의 Redis 구독 | 시세 알림을 받아 대기 주문의 지정가와 비교 | DB에 PENDING 주문이 있는 종목 |

예를 들어 삼성전자 대기 주문이 있으면 BE는 삼성전자 시세 알림을 받는다. 해당 종목의 대기 주문이 모두 체결되거나 취소되면 다음 구독 갱신에서 BE의 알림 구독을 해제할 수 있다. 이 동작은 시세 서버에 키움 수집을 중단하라고 요청하는 것이 아니다. 시세 서버의 수집은 계속된다.

현재 BE 코드에는 다음 중계 단계가 남아 있다.

```text
키움 → 시세 서버 → Redis의 price:{code}, orderbook:{code}
                         ↓ 담당 BE의 AiQuoteAdapter가 구독·검증
                  Redis의 quote:{code}:price/orderbook에 저장
                  Redis의 quotes:{code} 채널로 다시 발행
                         ↓ 각 BE의 RedisMarketStream이 구독
                  기존 주문 체결 서비스가 조건 비교·처리
```

즉, 현재 모든 BE가 AI 원본 채널을 직접 받아 체결하는 구조는 아니다. 기존 이름의 ‘수집 담당 BE’는 이 경로에서 **AI 시세를 BE용 데이터로 변환하고 중계하는 역할**을 한다. 키움 REST 요청의 담당 역할도 아직 남아 있다.

## 2. 이번에 제거한 부분

| 제거 대상 | 이전 역할 | 제거해도 되는 이유 |
|---|---|---|
| `RedisSubscriptionDemand` | 주문 저장 전 임시 구독 수요를 Redis에 등록·갱신·삭제 | 현재 주문 생성은 먼저 DB에 저장하고, 저장된 대기 주문으로 구독한다. 임시 수요를 등록하는 실제 호출자가 없었다. |
| `MarketStream`의 `retainRequest`, `renewRequest`, `releaseRequest`, `assertRequest` | 임시 수요 관리용 인터페이스 | 운영 코드에서 호출하지 않았다. |
| `MarketStream.whenSubscribed`, `RedisMarketStream.whenSubscribed`와 `waiting` 목록 | 구독 완료까지 요청을 기다리게 함 | 현재 주문 접수는 구독 완료를 기다리지 않는다. 구독 실패 시에도 주문은 DB에 대기 상태로 남는다. |
| `assertRestFallbackAllowed` | REST 대체 조회 허용 여부 확인 | 운영 호출자가 없었다. 현재 주문 체결의 시세 검증은 별도 코드에서 유지된다. |
| 임시 수요와 DB 대기 종목을 합치는 코드 | 저장 전후의 구독 수요를 연결 | 임시 수요 생산자가 없으므로 DB의 PENDING 종목만 조회하면 된다. |

`KiwoomStockStream` 자체의 `whenSubscribed`는 단독 WebSocket 검증 테스트가 사용하므로 남겼다. 이번에 제거한 구독 완료 대기는 공통 인터페이스와 Redis 구현의 미사용 경로다.

관련 테스트의 생성자와 검증 대상도 수정했다. 주문 구독 실패 테스트는 더 이상 호출하지 않는 `whenSubscribed` 대신 실제 `updateSymbols`에서 실패를 발생시켜, 주문 접수와 현금 예약이 유지되는지 검증한다.

### Redis 데이터와 권한에 미치는 영향

- `coord:kiwoom:{accepting}:request:{요청 ID}`와 `coord:kiwoom:{accepting}:index`를 더 이상 사용하지 않는다.
- 기존 키는 갱신하는 구버전 프로세스가 없다면 기존 TTL에 따라 정리된다. 요청 키는 30초, 색인은 60초다. 이번 작업은 Redis 데이터를 직접 삭제하지 않았다.
- 임시 수요 전용 `TIME`, `ZADD`, `ZRANGE`, `ZREM`, `ZREMRANGEBYSCORE` 권한 요구를 문서와 로컬 ACL 테스트에서 제거했다. 실제 배포 ACL은 변경하지 않았다.
- `coord:*` 접근과 Lua 권한은 담당 BE 소유권 관리 등에서 여전히 필요하다.
- DB 테이블·컬럼 변경이나 데이터 마이그레이션은 없다.

## 3. 아직 정리하지 않은 부분과 이유

### 3-1. 담당 BE의 AI 시세 중계와 활동 상태

대상: `AiQuoteAdapter`, `RedisCollectorHeartbeat`, `RedisQuoteStore.activeSource`, `RedisMarketStream.reconcile`.

현재 AI 원본 채널은 담당 소유권을 가진 BE가 구독한다. 다른 BE는 담당이 검증해서 다시 발행한 `quotes:{code}`를 받는다. 체결 시에도 담당 ID와 연결 ID가 유효한지 확인한다.

이 상태에서 담당 표시나 소유권 검사를 삭제하면 AI 시세를 중계하지 못하거나, 기존 체결 검증과 맞지 않게 된다. 모든 BE가 AI 채널을 직접 구독하도록 바꾸려면 수신·재연결·중복·시각 검증을 함께 설계하고 수정해야 한다. 따라서 단순 미사용 코드 삭제 범위를 넘어 이번에는 유지했다.

### 3-2. 기존 키움 REST 조회

대상: `KiwoomMarketClient`, `DirectKiwoomRestTransport`, `RemoteKiwoomRestTransport`, 내부 조회 컨트롤러.

| 조회 | 현재 사용처 | 바로 삭제할 경우 영향 |
|---|---|---|
| 종목 기본정보 `stockInfo` | `OrderMarketValidator` | 주문 생성 시 종목 검증 실패 |
| 현재가 `currentPrice` | `AccountHoldingsService`, `OrderBuyPriceService` | 보유 자산 평가·보유 비중 계산 실패 |
| 종목 상세정보 `stockDetails` | 보유 종목, 주문 내역, 대회 거래 내역 서비스 | 종목명 등 보완 조회 실패 |
| 특정 거래일 종가 `closingPrice` | 대회 일별 성과·종료 처리 서비스 | 해당 날짜의 평가와 정산 실패 |

실시간 주문 체결이 Redis 시세를 사용한다고 해서 위 조회까지 전부 Redis로 전환된 것은 아니다. 시세 서버가 제공할 종목정보·현재가·날짜별 종가의 키 또는 API, 필드와 누락 시 처리 방식을 확정한 뒤 호출자를 옮겨야 한다. 특히 특정 날짜의 종가를 최신 현재가로 대체해서는 안 된다.

### 3-3. 키움 토큰·TR 호출 제한·내부 REST 인증 설정

대상: `KiwoomTokenProvider`, `RedisKiwoomTokenStore`, REST 호출 제한 코드, `CollectorSecurityConfig`, `KIWOOM_COLLECTOR_URL`, `KIWOOM_COLLECTOR_SECRET` 등.

위 REST 호출이 남아 있어 인증 토큰, 호출 제한, 일반 BE에서 담당 BE로 요청을 보내는 주소와 비밀키도 아직 필요하다. 먼저 REST 사용처를 대체한 뒤 함께 제거해야 한다. 환경변수만 먼저 지우면 기존 조회 기능이 실패할 수 있다.

### 3-4. 기존 `KiwoomStockStream` WebSocket 클래스

운영 AI 시세 경로는 이 클래스의 `updateSymbols`로 키움 구독을 갱신하지 않는다. 다만 클래스 내부의 `StockPrice`, `OrderBook`, `QuoteLevel` 자료형을 현재 Redis 어댑터와 주문 코드가 참조하고, Spring 빈 설정과 단독 WebSocket 테스트도 연결돼 있다.

클래스 전체를 삭제하려면 공용 시세 자료형을 분리하고, 스트림 설정이 꺼진 경우의 빈 구성과 관련 테스트까지 정리해야 한다. 이번에는 단순 삭제로 참조와 실행 구성이 깨질 가능성이 있어 유지했다.

### 3-5. 어댑터의 메모리 상태와 BE용 Redis 캐시

`AiQuoteAdapter`에는 `sessions`, `acknowledged`, `prices`, `books`가 남아 있다. 연결 세대와 구독 확인을 관리하고, 이전 시세와 비교해 중복·역행 알림을 걸러 내는 용도다. 따라서 현재 구현을 ‘시세 관련 인메모리 상태가 전혀 없다’고 설명하면 정확하지 않다.

AI 원본 `price:*`, `orderbook:*`와 별도로 BE 검증 결과를 `quote:*`에 저장하고 `quotes:*`로 전달하는 과정도 남아 있다. 이 캐시와 메모리 상태를 제거하려면 담당 BE 중계 구조를 바꾸면서 같은 검증을 어디에서 수행할지 결정해야 한다.

## 4. 앞으로도 필요한 기능

다음은 이름이나 위치를 바꿀 수는 있어도, 미사용 코드로 취급해 제거할 대상은 아니다.

- DB 대기 주문 종목 조회: BE가 어떤 종목의 알림을 처리할지 결정한다.
- 시세의 날짜·신선도 검증: 과거 시세를 새 장의 체결 가격으로 쓰지 않도록 한다.
- 재구독 ACK와 실제 시세 알림 구분: 연결 확인만으로 저장된 가격을 새 체결 이벤트로 만들지 않는다.
- DB 계좌 잠금과 주문 상태 검사: 여러 BE가 같은 시세를 받아도 같은 주문을 중복 체결하지 않도록 한다.

## 5. 검증 결과와 범위

이번 코드 정리 후 실행한 로컬 검증:

| 검증 | 결과 |
|---|---|
| `gradlew.bat build --no-daemon` | 성공, SpotBugs 포함 |
| 기본 테스트 | 총 339개 중 320개 통과, 19개 조건부 제외, 실패 0개 |
| 로컬 Valkey 추가 테스트 | `RedisSharedMarketLiveTests`, `RedisAclLiveTests`, `OrderExecutionEventTests` 총 7개 통과 |
| 추가 테스트 내용 | Pub/Sub 시세 전달, 재구독 시 스냅샷 재생 방지, 제한된 ACL, 두 BE의 중복 체결 방지 등 |
| 변경 파일 공백 오류 검사 | `git diff --check` 통과 |

실제 dev 서버의 실행 설정, 배포 Valkey의 IAM·TLS·ACL, 키움 실서버 및 AI 시세 서버와의 실시간 종단 간 연결은 이번 검증에 포함하지 않았다. 커밋·푸시·배포도 수행하지 않았다.

## 6. 후속 정리 순서

1. 시세 서버와 기존 REST 조회 대체 규격을 합의한다.
2. 필요하다면 각 BE가 AI 채널을 직접 구독하도록 전환하고 기존 시세 검증을 보존한다.
3. 공용 시세 자료형과 빈 구성을 정리한 뒤 기존 WebSocket 코드를 제거한다.
4. REST 호출자가 모두 전환된 것을 확인한 뒤 키움 토큰·호출 제한·내부 REST·환경변수를 제거한다.

이 순서는 후속 작업 제안이며 이번에 구현한 내용은 아니다. 상세 시세 규격은 [AI Valkey 현재가·호가 호환](ai-quotes.md), 권한은 [Valkey ACL 요구 사항](valkey-acl-requirements.md)을 참고한다.
