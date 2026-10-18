# AI Valkey 현재가·호가 호환

기존 연동의 삭제 내역과 남은 의존성은 [기존 키움 연동 코드 정리](legacy-kiwoom-cleanup.md)를 참고한다.

기준: BE dev `5ac6a7c`, AI dev `8739d1332e4bf4e7b76f397a1914bfc0a194d470`.
AI 저장소와 순위 API·키는 변경하지 않는다.

| AI 원본 | BE 대응 |
|---|---|
| `price:{code}` 키와 동일 이름 채널 | `AiQuoteAdapter`의 스냅샷 GET 및 현재가 알림 구독 |
| `orderbook:{code}` 키와 동일 이름 채널 | 스냅샷 GET 및 호가 알림 구독 |
| `code` | `stockCode` (요청 키·채널 종목과 일치 필수) |
| `price`, `change`, `rate` | `currentPrice`, `change`, `changeRate`; 부호·소수 정밀도 보존 |
| `volume` | `StockPrice.volume`; 같은 거래일의 역행·중복 판단 |
| `asks/bids[].price`, `qty` | `QuoteLevel.price`, `quantity`; 음수·정수 범위 초과·소수 수량 거부 |
| `timestamp` | 전체 날짜·오프셋을 `exchangeTimestamp`에 보존; 한국 시각의 `tradeTime`/`quoteTime`도 생성 |
| 실제 Pub/Sub 수신 시각 | BE 시계의 `receivedAt` |
| 스냅샷 GET | `receivedAt=null`; 체결 이벤트를 발행하지 않음 |

AI 원본은 TTL이 없으므로 GET 시각을 시세 수신시각으로 만들지 않는다. `MarketStream.latest/latestOrderBook`은 검증된 BE 캐시가 없을 때 AI 스냅샷을 조회할 수 있다. 주문용 `latestQuote/latestBookQuote`는 실제 알림으로 생성한 BE 캐시만 사용한다.

## BE의 기존 소유권 경계 유지

`kiwoom.stream.enabled=true`일 때 기존 `RedisMarketStream`과 어댑터를 연결한다. 별도 전환 설정은 없다. 기존 `kiwoom.collector.enabled=true` 담당 BE가 필요하다. 담당은 DB PENDING 주문 종목의 AI 채널을 개별 구독한다. 운영 시세 구독을 위해 `KiwoomStockStream.updateSymbols`를 호출하지 않는다. 키움 REST 종목정보·일봉과 토큰의 담당 정책은 유지한다.

담당은 `coord:kiwoom:collector` 소유권을 확인하고 수신한 AI JSON을 검증한 뒤 BE 세션을 붙인다. 이 `Source`는 **BE 중계 연결**을 식별하며 AI 수집기의 소유권을 의미하지 않는다. 검증된 값은 기존 `quote:{code}:price/orderbook`, 등록은 `quote:{code}:subscription`, 내부 알림은 `quotes:{code}`를 사용한다. AI 키를 덮어쓰거나 삭제하거나 AI 채널에 발행하지 않는다. 구독 대상은 DB의 PENDING 주문 종목으로 정한다. 접수 중 임시 수요 키(`coord:kiwoom:{accepting}:*`)와 구독 완료 대기는 제거했다. 주문은 먼저 저장하며 시세 구독에 실패해도 대기 상태를 유지한다. 이전 임시 수요 키는 기존 TTL(요청 30초, 색인 60초)로 정리되므로 별도 데이터 삭제나 DB 변경은 필요 없다.

재연결 ACK는 새 BE 세션을 만들며 이전 캐시를 체결에 사용하지 않는다. 같은 구독 세대의 현재가·호가 ACK는 세션을 공유한다. ACK와 스냅샷 읽기에서는 새 체결 이벤트를 만들지 않는다. 거래소시각·거래량이 역행하거나 중복된 현재가를 재발행하지 않는다. 콜백은 단일 처리 스레드와 기존 256개 대기열로 순서를 보존한다.

## 체결 검증

AI 알림은 한국 날짜가 수신 날짜와 같고 거래소시각이 수신시각 기준 과거 5초부터 미래 1초 이내여야 한다. 주문 판단 시에는 기존 장중·계좌 잠금·상태·담당·세션 검사에 더해 AI 현재가의 전체 거래소 날짜와 5초 신선도를 다시 확인한다. 호가도 전체 거래소 날짜를 검사하며 기존 5초 기준을 유지한다. 신규 이벤트로 대기 주문을 판단할 때 주문 접수가 거래소시각 뒤라면 건너뛴다. 초 단위 거래소시각 때문에 같은 초에 접수된 주문은 다음 알림을 기다릴 수 있다.

Pub/Sub 누락은 재생하지 않는다. AI가 게시하지 않는 종목이나 장외에는 새 실행용 시세가 없으므로 주문을 유지한다. AI JSON에는 수집기 세션·연결 heartbeat가 없어 AI 원본 연결의 생존을 증명할 수 없다. 지연된 스냅샷으로 이를 추정하지 않으며 실제 시세의 시각으로 제한한다. 배포 Valkey의 신뢰 경계와 ACL을 적용해야 한다.

## 제거한 시장 지수 기능

`KospiIndexController`, `KospiIndexService`, `RedisKospiStore`, 키움 지수 조회와 `ka20001` 내부 전달 허용, 자동 갱신 설정 및 해당 테스트·혼합 테스트 호출을 제거했다. 기존 운영 Redis의 지수 키는 이 변경에서 직접 삭제하지 않는다.

## 권한과 검증

BE가 AI 키에 GET, AI 채널에 SUBSCRIBE/UNSUBSCRIBE할 수 있어야 한다. 기존 키·채널 권한에 `%R~price:* %R~orderbook:* &price:* &orderbook:*`를 추가한다. 기존 BE 저장·내부 발행 권한은 유지한다. 패턴 구독과 순위 키 권한은 추가하지 않는다.

일반 검증: `./gradlew.bat test`.
로컬 Valkey 검증: `REDIS_LIVE_TEST=true`를 설정하고 `AiQuoteAdapterLiveTests`, `RedisQuoteStoreLiveTests`를 실행한다. 로컬 테스트는 전용 `TST011` 키와 담당 키의 기존 값이 없을 때만 실행하며 AI 서버·키움 API를 호출하지 않는다.

2026-10-17 검증 결과:

- 전체 `test`: 339개 중 319개 통과, 외부 연결 선택 테스트 20개 건너뜀, 실패 0.
- 로컬 Valkey를 활성화한 어댑터·공유 시세·다중 BE 주문 이벤트·체결 회귀 테스트: 26개 통과, 건너뜀 0.
- `build -x test`: 빌드 및 `spotbugsMain` 통과. 테스트는 위 명령으로 별도 실행했다.
- 실제 AI 서버·키움 연결 및 배포 Valkey ACL/TLS/IAM 환경은 이번에 검증하지 않았다.

## 평가용 현재가 직접 조회 (2026-10-18)

`RedisMarketPrice.currentPrice(code)`는 시세 서버가 공급한 `price:{code}` JSON을 직접 GET한다. 담당 BE의 소유권이나 WebSocket 활성화 설정에 의존하지 않으며 Redis에 저장·삭제·재발행하지 않는다.

- 적용: 계좌 목록·상세의 자산 평가(`AccountService`), 보유 종목 평가(`AccountHoldingsService`), 매수 체결 후 리포트의 다른 보유 종목 평가(`OrderBuyPriceService`). 계좌 자산 평가를 재사용하는 대회 목록 등의 조회도 같은 경로를 사용한다.
- 검증: 요청 종목코드 일치, 양수인 정수 가격과 long 범위, 오프셋이 있는 `timestamp` 형식.
- 장외·주말 평가에도 마지막 저장 가격을 사용한다. 이 조회에는 가격 경과시간 제한을 추가하지 않았다. 수집이 멈춘 경우 오래된 가격으로 평가될 수 있으며 공급 상태 표시는 별도 정책이 필요하다.
- 누락·형식 오류·Redis 장애는 `IllegalStateException`으로 전달한다. 키움 REST로 대체 조회하거나 임의 가격을 넣지 않는다. 계좌·보유 조회는 기존 503 처리, 매수 리포트는 기존 비중 기록 생략 정책을 유지한다.
- 이 스냅샷을 체결 이벤트로 발행하지 않는다. 실제 주문 체결은 기존 장중·거래소시각·수신시각 검증을 계속 사용한다.

참고한 [시세 서버 HTTP 명세](https://stock-spoon.com/market/openapi.json)는 `/prices`, `/orderbooks`, `/bars/{code}`, `/indices/{name}` 등을 제공한다. HTTP 명세만으로 Redis 키·자료형을 알 수는 없으므로 현재가 키는 기존 AI 연동에서 확인한 `price:{code}` 규격을 사용했다. 새 환경변수나 시세 서버 HTTP 호출은 추가하지 않았다. BE에 `price:*` GET 권한이 필요하다.

종목명·업종·시장 구분 및 날짜별 종가의 Redis 규격은 이 명세에 없다. 해당 REST 경로와 인증·토큰은 아직 유지한다. `/bars/{code}?timeframe=1d`가 있다고 해서 Redis 저장 키나 종가 확정 여부를 추정하지 않는다. 담당 BE의 기존 Pub/Sub 중계 구조도 이번 평가용 현재가 변경과 별개로 남아 있다.
