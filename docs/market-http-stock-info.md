# 종목정보 시세 서버 HTTP 전환

2026-11-04: 종목정보의 운영 조회 출처를 키움 REST에서 AI 시세 서버 HTTP로 변경했다.

## 호출 흐름

```text
종목명 API / 보유 조회 / 주문·대회 거래내역
 → KiwoomMarketClient.stockDetails(code)
 → 기존 Redis quote:{code}:metadata 캐시(7일)
 → 캐시 미스: MarketStockClient.stockInfo(code)
 → GET {MARKET_HTTP_BASE_URL}/stocks/{code}
 → 필요한 필드 검증·변환 → 캐시 저장 → 기존 응답

관심종목 등록
 → StockCatalogService.resolveStockId(code)
 → DB에 없는 경우 KiwoomMarketClient.stockMaster(code)
 → MarketStockClient.stockInfo(code) → 동일 HTTP API
 → DB 종목 마스터 등록
```

기존 서비스·DTO와 Redis 캐시 형식을 유지하기 위해 시세 서버 응답을 경계에서 변환한다.
`code`, `name`은 같은 이름을 사용하고 `sector_name`을 `upName`으로 변환한다.
AI 시세 서버는 현재 KOSPI 종목을 수집한다. `market=KOSPI`를 기존 DB 코드 `0`으로 변환하고 다른 시장은 오류로 처리한다. 시장 지원 확대 시 공급 규격과 저장값을 함께 확인해야 한다.
`corp_code`, `english_name`, `synced_at`, `themes`는 현재 소비 기능에 필요하지 않아 저장하지 않는다.

## 설정과 실패

- `MARKET_HTTP_BASE_URL`: 기본값 `https://stock-spoon.com/market`. 연결 3초, 응답 5초 제한. 리다이렉트하지 않는다.
- GET 요청에 키움 토큰·앱 키·내부 중계 비밀키를 보내지 않는다.
- 코드 불일치, 이름/업종 자료형 오류, 알 수 없는 시장, HTTP 오류는 기존 서비스의 실패 처리로 전달한다. 키움 재조회는 하지 않는다.
- 기존 내부 `/internal/kiwoom/query`의 `ka10100` 요청도 시세 서버 경로로 처리한다. `/metadata`는 같은 경로의 캐시를 사용한다.
- DB에 이미 있는 종목과 유효한 기존 메타데이터 캐시는 계속 사용한다. 새 HTTP 조회를 확인하려면 캐시 미스 상황을 사용해야 한다.

## 종가 기준: 수정주가 (2026-11-04 최종 결정)

원주가는 해당 거래일에 실제 거래된 당시 가격이다. 수정주가는 액면분할·병합·배당 등 공급자의 조정 기준에 따라 과거 가격을 다시 산정한 값이다. 주식 수량이나 취득원가가 조정되지 않은 상태에서 수정주가를 평가에 사용하면 당시 원주가 기준 결과와 차이가 날 수 있다.

기존 BE는 키움 `ka10081`에서 `upd_stkpc_tp=0`인 원주가를 사용했다. AI 시세 서버는 `upd_stkpc_tp=1`인 수정주가를 수집하며 API에 원주가 선택 파라미터가 없다. 사용자는 원주가를 우선 요청했으나, 공급되지 않는 경우 수정주가를 사용하도록 최종 결정했다. 따라서 운영 대회 종료 평가와 일별 성과 계산의 종가를 수정주가로 전환한다.

```text
CompetitionCompletionService / CompetitionDailyPerformanceService
 → KiwoomMarketClient.closingPrice(code, date)
 → MarketStockClient.closingPrice(code, date)
 → GET /market/bars/{code}?timeframe=1d&limit=1000[&cursor=...]
 → UTC 기준 거래일이 정확히 같은 봉의 close
```

공급 코드의 일봉 날짜는 UTC 자정으로 저장된다. next_cursor를 따라 최대 100페이지를 조회하며 반복·빈 커서는 오류 처리한다. 요청 거래일 누락·HTTP 장애·형식 오류 시 키움이나 다른 날짜 가격으로 대체하지 않는다. close는 양수이며 long으로 정확히 표현되는 정수만 허용한다. 소수 가격을 임의 반올림하지 않는다.

기존에 DB에 저장된 정산 결과·일별 성과는 일괄 재계산하지 않는다. 전환 후 새로 계산하는 결과는 수정주가를 사용하므로 기존 원주가 결과와 기준이 섞일 수 있다. 공급자가 과거 수정주가를 갱신하면 같은 날짜의 추후 조회 가격도 달라질 수 있으며, 저장된 성과는 자동으로 재평가하지 않는다. 당시 체결가·취득원가·보유 수량을 수정주가 기준으로 소급 변환하는 변경은 포함하지 않는다.

운영 종목정보·종가 요청은 시세 서버 HTTP를 사용하며 현재가·호가는 기존 Redis를 사용한다. 키움 연동 클래스와 단독 연동 테스트, 담당 BE 호환 엔드포인트는 남아 있지만 운영 종가에서 직접 키움을 호출하지 않는다. 내부 호환 종가 응답도 수정주가이므로 원주가 응답으로 해석하면 안 된다.

## 근거

- [종목 API](https://stock-spoon.com/market/scalar#GET/stocks/{code})
- [AI 종목 조회](https://github.com/100-hours-a-week/KTB4-11th-AI/blob/dev/services/market-http/src/market_http/repositories/stocks.py)
- [AI 일봉 수집 옵션](https://github.com/100-hours-a-week/KTB4-11th-AI/blob/dev/services/market-collector/src/market_collector/kiwoom/official.py)

종목정보 HTTP는 PostgreSQL, 일봉 HTTP는 QuestDB를 조회한다. 이 변경은 HTTP 소비 전환이며 Redis 직접 조회를 추가한 것이 아니다.

## 기존 설정 충돌 점검과 배포 기준

운영 KiwoomMarketClient는 종목 HTTP 클라이언트와 캐시만 주입받는다. 사용하지 않는 키움 토큰/전송 빈을 생성하지 않으므로 과거 담당 URL의 형식 오류가 운영 HTTP 경로의 시작을 막지 않는다.

키움 토큰/전송·담당 heartbeat·내부 중계 컨트롤러·전용 보안 설정은 `KIWOOM_LEGACY_ENABLED=true`인 경우에만 활성화된다. 기본값은 false다. 과거 `KIWOOM_COLLECTOR_ENABLED=true`가 남아 있어도 이 추가 플래그 없이는 담당 기능을 활성화하지 않는다. 레거시 모드는 단독 연동 테스트·이전 중계 호환 시험용이며 새 업무 조회는 여전히 HTTP를 사용한다.

일반 배포에는 `MARKET_HTTP_BASE_URL`과 기존 DB·Redis 연결을 사용한다. Redis 현재가·호가 수신에는 `KIWOOM_STREAM_ENABLED=true`를 유지한다. 기존 키움 앱 키·담당 URL·비밀키는 HTTP 조회에 필요하지 않다. 외부 HTTP 오류는 기존 503/정산 롤백 경로로 전달된다.

충돌 점검 범위는 운영 빈 주입, 오래된 환경변수, DB/캐시 필드, 내부 API 인증, UTC 거래일, 페이지 조회, 가격 정밀도, 장애 시 키움 우회 여부다. 기존 stocks 행·7일 캐시와 FE DTO 형식은 유지한다. 수정주가 채택으로 가격 기준은 의도적으로 변경되며 위 설명의 평가 차이를 수용한 상태다.

남은 운영 확인은 실제 배포 환경에서 시세 서버로 나가는 HTTP 경로/시간제한/실제 응답, Redis 권한, 장 마감 일봉 갱신 시점 검증이다. API에 종가 확정 플래그가 없으므로 마감 후 공급 데이터의 완결성은 시세 서버 수집에 의존한다. 단독 외부·실제 Redis 조건부 테스트는 기본 회귀 테스트에서 제외된다.

## 실제 공개 서버 연결 검증 (2026-11-04)

개발 PC에서 공개 서버의 `GET /market/stocks/005930`와 `GET /market/bars/005930?timeframe=1d&limit=3` 응답을 확인했다. 종목코드·이름·KOSPI·업종 필드가 제공되며, 일봉의 timestamp는 UTC 자정이고 close는 JSON 숫자로 제공된다.

`MARKET_HTTP_LIVE_TEST=true`로 `MarketHttpLiveTests`를 실행했다. 실제 운영 팩터리가 구성한 Java RestClient로 종목정보를 조회·변환하고 2026-11-02의 수정주가 종가를 읽는 테스트가 통과했다. 테스트는 공개 GET만 실행하며 DB·Redis·주문 처리를 실행하지 않는다.

이는 개발 PC에서 공급 서버까지의 연결 검증이다. 배포 서버의 네트워크·Redis 권한·장 마감 수집 완료 여부를 검증한 것은 아니다. 이번 작업은 사용자 지시에 따라 커밋·검증까지만 진행하며 배포하지 않는다.
