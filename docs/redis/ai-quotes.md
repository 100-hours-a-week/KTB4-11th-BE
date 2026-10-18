# AI Valkey 현재가·호가 연동

최종 갱신: 2026-10-18. 각 BE가 시세 서버의 Redis 원본 키와 채널을 직접 사용한다. 기존 담당 BE의 시세 재저장·재발행 단계를 제거했다. 변경 내역과 이전 설계 기록은 [기존 키움 연동 정리](legacy-kiwoom-cleanup.md)를 참고한다.

## 데이터 흐름

```text
키움 → 시세 서버 → Redis price:{code}, orderbook:{code}
                       ├─ BE 1 → 자체 주문 체결 서비스
                       ├─ BE 2 → 자체 주문 체결 서비스
                       └─ BE 3 → 자체 주문 체결 서비스
```

시세 서버가 키움의 코스피200 시세를 수집한다. 각 BE의 `OrderSubscriptionService.refresh()`는 DB의 PENDING 주문 종목을 읽고 `RedisMarketStream.updateSymbols()` → `AiQuoteAdapter.updateSymbols()`로 해당 Redis 채널을 개별 구독한다. 이 목록은 BE가 어떤 주문을 판단할지 정하는 목록이며 시세 서버의 키움 수집 종목을 변경하지 않는다.

각 BE에서 알림을 수신하면 `AiQuoteAdapter.onMessage()`가 검증한 뒤 해당 프로세스의 Spring `QuoteEvent`로 전달한다. `OrderExecutionListener.onQuote()`가 대기 주문을 비교하고 기존 체결 트랜잭션을 실행한다. Spring 이벤트는 해당 BE 안에서만 전달된다. BE는 시세를 Redis에 다시 저장하거나 `quotes:*`로 재발행하지 않는다.

## 원본 규격

| AI 원본 | BE 대응 |
|---|---|
| `price:{code}` 키와 같은 이름의 채널 | 현재가 스냅샷 GET 및 현재가 알림 구독 |
| `orderbook:{code}` 키와 같은 이름의 채널 | 호가 스냅샷 GET 및 호가 알림 구독 |
| `code` | 요청 키·채널의 종목과 일치해야 함 |
| `price`, `change`, `rate` | 현재가·전일 대비·등락률, 부호·소수 정밀도 보존 |
| `volume` | 같은 거래일의 역행·중복 시세 판단 |
| `asks/bids[].price`, `qty` | 호가 가격·수량. 음수·분수 수량·범위 초과 거부 |
| `timestamp` | 날짜·오프셋을 보존한 거래소시각 `exchangeTimestamp` |
| 실제 Pub/Sub 수신 시각 | BE 시계의 `receivedAt` |
| 스냅샷 GET | `receivedAt=null`, 체결 이벤트를 만들지 않음 |

원본 키를 덮어쓰거나 삭제하거나 TTL을 변경하지 않는다. AI 채널에 발행하지 않으며 `PSUBSCRIBE`도 사용하지 않는다.

## BE의 로컬 구독 상태

담당 BE의 `coord:kiwoom:collector`, 공유 종목 등록 `quote:{code}:subscription`, 공유 시세 `quote:{code}:price/orderbook`, 중계 채널 `quotes:{code}`는 이 시세 경로에서 사용하지 않는다. `RedisQuoteStore`와 직접 키움 WebSocket의 관련 코드는 단독 검증 등 기존 경로에 남아 있으므로 클래스 자체를 제거한 것은 아니다.

대신 각 BE 메모리에 다음 상태만 유지한다.

- 구독 대상 종목과 채널별 ACK 확인
- BE 실행 ID와 종목별 Redis 구독 세대 ID
- 해당 구독 세대에서 실제 수신한 마지막 현재가·호가 이벤트: 중복·역행 검사와 신규 주문의 최초 체결 판단용

이 값은 여러 BE가 공유하는 데이터가 아니다. `QuoteStore.Source`의 기존 필드명 `collectorId`는 이 경로에서는 각 BE의 수신자 ID를 뜻하며 키움 수집 담당 소유권을 뜻하지 않는다.

구독 해제·종료 시 로컬 상태를 지운다. 재연결 ACK는 새 세대를 만들고 이전 체결용 이벤트를 지운다. 현재가와 호가의 같은 구독 세대 ACK는 세션을 공유한다. 구독 ACK나 저장된 스냅샷만으로 새 체결 이벤트를 만들지 않는다. 지연 처리 중 재연결·해제되면 체결 직전의 세대 검사에서 이전 이벤트를 거절한다.

## 체결 검증과 장애

- 수신 시 한국 날짜가 같고 거래소시각이 수신시각 기준 과거 5초부터 미래 1초 이내여야 한다.
- 가격·수량·종목코드·시각 검증과 중복·역행 검사를 보존한다.
- 실제 체결에서는 장중 여부, 거래소 날짜와 5초 신선도, 주문 생성 시각, BE의 구독 세대를 다시 검사한다.
- 이전 거래일·주말 예약 주문은 당일 장중의 새 시세 이벤트를 기다린다.
- 각 BE가 같은 시세를 받아도 DB의 계좌 잠금과 주문 상태 검사로 같은 주문의 중복 체결을 막는다.
- 수신 콜백은 BE마다 1개 스레드와 최대 256개 대기열을 사용한다. Pub/Sub 누락은 재생하지 않는다.
- Redis 수집기 쪽 세션·heartbeat 정보는 AI 원본 규격에 없으므로 해당 연결 생존 자체를 증명할 수 없다. 시세의 시각 검증과 Redis 구독 세대 검증을 사용한다.

일반 BE는 `KIWOOM_STREAM_ENABLED=true`로 직접 구독을 활성화한다. 시세 수신만을 위해 `KIWOOM_COLLECTOR_ENABLED=true`를 설정할 필요는 없다. 다만 기존 종목정보·종가 REST 전달에는 여전히 담당 BE와 URL·비밀키가 필요하다. 키움 REST의 담당 상태와 Redis 직접 시세 수신은 별개다.

## 평가용 현재가 직접 조회

`RedisMarketPrice.currentPrice(code)`는 `price:{code}` JSON을 GET한다. 계좌 목록·상세 자산 평가, 보유 종목 평가, 매수 리포트의 다른 보유 종목 평가에 사용한다. 이 경로도 담당 BE나 WebSocket 활성화에 의존하지 않는다.

장외·주말에는 마지막 저장 가격을 평가에 사용할 수 있다. 코드 일치·양수인 정수 가격·long 범위·오프셋이 있는 timestamp 형식을 검사하며 추가 경과시간 제한은 없다. 공급이 멈추면 오래된 가격으로 평가될 수 있다. 스냅샷을 체결 이벤트로 승격하지 않는다.

누락·형식 오류·Redis 장애 시 키움 REST로 대체하거나 임의 가격을 넣지 않는다. 계좌·보유 조회는 기존 503 처리, 매수 리포트는 기존 비중 기록 생략 정책을 유지한다.

## 남아 있는 REST와 권한

[시세 서버 HTTP 명세](https://stock-spoon.com/market/openapi.json)는 `/prices`, `/orderbooks`, `/bars/{code}`, `/indices/{name}` 등을 제공한다. 종목명·업종·시장 구분과 날짜별 종가의 Redis 키 규격은 이 명세에 없다. 현재 남아 있는 종목명·업종 및 날짜별 종가 조회는 기존 REST 경로를 유지한다. 주문 시장 검증 정책은 이번 시세 수신 변경 대상이 아니다. 내부 REST는 `ka10100`, `ka10081`만 허용하며 `ka10001`은 차단한다.

각 BE는 `%R~price:* %R~orderbook:* &price:* &orderbook:*` 등 원본 키 GET 및 원본 채널 SUBSCRIBE/UNSUBSCRIBE 권한이 필요하다. BE의 이 시세 수신 경로에는 원본 쓰기·삭제·PUBLISH 권한이 필요 없다. 기존 메타데이터·토큰·REST 담당 상태 권한까지 삭제하면 안 된다. IAM 연결 인증과 데이터 ACL은 별개다.

새 환경변수나 시세 서버 HTTP 호출은 추가하지 않았다. 일반 BE 전환 전 원본 채널 권한을 준비하고, 미전환 BE가 남아 있는 동안 기존 시세 중계 담당을 중단하면 해당 BE의 체결이 멈출 수 있다. 배포 Valkey IAM·TLS·ACL과 실제 AI 공급 연결은 별도 검증 대상이다.

## 로컬 검증 (2026-10-18)

- 전체 회귀 빌드: 테스트 339개 중 320개 통과, 19개 조건부 제외. SpotBugs·패키징 성공.
- 마지막 콜백 예외 처리 보완 후에는 `build test --tests '*AiQuoteAdapterTests'`로 최신 소스의 정적 검사·패키징 및 단위 테스트 8개를 확인했다. 구독 등록 실패 후 재시도, 구독 해제·재연결 이전 이벤트 거절, 잘못된 값 거절, 로컬 체결 리스너 실패 후 다음 시세 처리 등을 포함한다.
- 로컬 Valkey의 `AiQuoteAdapterLiveTests`와 `OrderExecutionEventTests` 통과. 두 BE 원본 수신, 중계 채널 재발행 없음, 원본 키 보존, 재연결 시 스냅샷 재생 없음, 두 BE의 DB 중복 체결 방지를 확인했다.
- 별도 JVM 3개의 정상 시나리오와 Redis 연결 장애·재구독 시나리오를 각각 검증했다. REST 담당 소유권이 만료돼도 외부 발행기의 원본 알림으로 대기 주문 체결이 복구되는 것을 확인했다.
- 기존 중계 전용 테스트는 원본 구독·로컬 이벤트·DB 체결 테스트로 대체했다. 직접 키움 단독 검증 테스트는 운영 직접 구독 경로와 구분했다.
- 100개 주문 부하 시나리오, 실제 키움·AI 공급기 연결, 배포 IAM·TLS·ACL은 이번 단계에서 실행하지 않았다. 커밋·푸시·배포도 수행하지 않았다.
