# 키움 종목 현재가 WebSocket 구독

## 범위

현재가는 주식체결(0B)을 구독해 수신한다. 첫 검증 종목은 삼성전자 005930이다.
기존 토큰 발급/캐시를 재사용하며 Java 21의 HttpClient/WebSocket을 사용한다.
추가 의존성은 없다. 호가(0D), FE 조회/푸시 API, 주문·보유종목에 따른 동적 구독,
모의체결 엔진은 아직 구현하지 않았다.

## 흐름

1. 설정에서 활성화한 경우 5초마다 연결 상태를 점검한다.
2. 유효한 토큰을 받고 wss://api.kiwoom.com:10000/api/dostk/websocket 에 연결한다.
3. LOGIN 메시지에 토큰을 담아 전송한다.
4. LOGIN return_code=0을 확인한 뒤 REG를 전송한다.
5. REG return_code=0을 확인한 뒤 REAL의 0B 데이터만 처리한다.
6. 서버가 보내는 JSON PING은 원문 그대로 돌려준다.
7. 연결/인증/구독 실패 또는 종료 시 가격 캐시를 비우고 다음 점검에서 다시 연결·구독한다.
8. LOGIN에서 거부된 토큰은 캐시에서 폐기한다. 이미 새로 갱신된 토큰은 건드리지 않는다.

인증·구독 대기 제한은 연결 시도 시작부터 10초, 연결의 메시지 무수신 제한은 90초다.
90초는 키움이 보장한 주기가 아니라 이번 구현의 운영상 감시 기준이다.
PING도 연결의 생존 신호로 계산하므로 체결이 없는 종목을 곧바로 연결 장애로 판단하지 않는다.
서버가 종료되면 연결을 중단하고 재연결하지 않는다. 분할된 텍스트 메시지는 조립 후 해석하며
한 메시지는 최대 1 MiB까지 처리한다. 전송은 순서대로 실행하고 5초 제한을 둔다.

구독 메시지(인증 성공 후 전송):

```json
{
  "trnm": "REG",
  "grp_no": "1",
  "refresh": "1",
  "data": [{"item": ["005930"], "type": ["0B"]}]
}
```

## 수신 데이터

| 키움 필드 | 내부 필드 | 의미 |
| --- | --- | --- |
| item | stockCode | 종목코드 |
| values.10 | currentPrice | 현재가. 가격의 방향 부호를 제거해 양수로 저장 |
| values.11 | change | 전일 대비. 부호 유지 |
| values.12 | changeRate | 등락률(%). 부호 유지 |
| values.20 | tradeTime | 키움 체결시간 HHmmss를 LocalTime으로 해석 |
| 없음 | receivedAt | 백엔드 수신 시각 Instant(UTC) |

체결시간에는 날짜를 임의로 붙이지 않는다. receivedAt은 거래소의 체결 시각이 아니다.
동일 가격으로 체결돼도 새 체결 메시지를 처리한다. 다른 종목/다른 타입은 무시한다.
유효하지 않은 가격/시간/JSON 수신 시 연결과 캐시를 폐기하고 재연결한다.

KiwoomStockStream.latest(stockCode)는 현재 연결이 구독 성공 상태이고
그 연결에서 받은 가격이 있을 때 Optional<StockPrice>를 반환한다.
state()로 연결 상태를 조회한다. 아직 종목 REST 엔드포인트는 없다.

구독 성공은 장중 거래 가능이나 가격의 시장 유효성을 뜻하지 않는다.
장외에는 REAL이 없을 수 있고, 장중에도 거래가 없으면 가격의 체결시간이 오래될 수 있다.
체결 엔진을 연결할 때 정규장/휴장/거래정지/시세 상태 판정을 추가해야 한다.
현재 모듈은 주문을 실행하지 않는다.

## 설정

환경변수 또는 .env.local에 아래 비밀이 아닌 설정을 추가한다.

```properties
KIWOOM_STREAM_ENABLED=true
KIWOOM_STREAM_SYMBOLS=005930
```

종목은 쉼표로 구분하며 중복은 제거한다. 첫 단계에서는 6자리 KRX 종목코드만 허용한다.
기본값은 비활성화다. 설정 없이 시작하는 테스트/배포에서 실전 서버에 자동 접속하지 않게 했다.
키·시크릿과 허용 IP는 기존 키움 연결과 동일하다.
구독과 가격은 서버 한 프로세스의 메모리에만 존재한다. 여러 서버를 운영하면 별도 구독 소유권
설계가 필요하다. 키움 원문/토큰은 로그에 기록하지 않는다.

## 검증

자동 테스트는 가짜 WebSocket으로 인증→구독 순서, 분할 메시지, 가격 부호/시간 파싱,
PING 응답, 연결 종료 후 값 폐기, 재구독, 이전 연결 메시지 무시, 실패와 시간 초과를 검증한다.
토큰 무효화가 이미 갱신된 토큰을 지우지 않는지도 검증한다.

```powershell
.\gradlew.bat test --no-daemon
```

실전 LOGIN/REG만 검증하려면:

```powershell
$env:KIWOOM_STREAM_LIVE_TEST = 'true'
try {
    .\gradlew.bat test --tests '*KiwoomStockStreamLiveTests' --rerun-tasks --no-daemon
} finally {
    Remove-Item Env:KIWOOM_STREAM_LIVE_TEST
}
```

이 테스트는 삼성전자 005930을 구독하고 종료하며 주문을 전송하지 않는다.
키움 구독 응답 성공과 REAL 수신 성공은 구분한다. 장외의 REAL 미수신은 장중 수신 검증을
대신할 수 없다.

## 공식 근거

- [키움 REST API 가이드](https://openapi.kiwoom.com/guide/apiguide): 국내주식 → 실시간시세 → 주식체결(0B)
- [키움 공식 0B 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/국내주식/실시간시세/subscribe_domestic_stock_trade_async.py): 경로, REG, 필드 매핑
- [키움 실시간 조회 입문](https://openapi.kiwoom.com/m/guide/index?dummyVal=0): LOGIN과 JSON PING
  (입문 페이지의 접속 경로 표기는 개별 0B 명세와 달라 개별 명세의 /api/dostk/websocket을 사용한다.)


## 2026-09-27 실전 확인

삼성전자 005930의 LOGIN 및 REG 성공 응답을 확인했다. REAL 수신은 없었다.
일요일 장외 검증이므로 장중 현재가 수신·시장 시각의 유효성은 아직 검증하지 못했다.
첫 시도는 연결 준비 단계에서 실패했고, 토큰 발급을 분리한 재검증은 성공했다.
첫 실패의 세부 원인은 확정하지 않았다. 연결 준비 실패는 다음 점검에서 재시도하도록 구현했다.
