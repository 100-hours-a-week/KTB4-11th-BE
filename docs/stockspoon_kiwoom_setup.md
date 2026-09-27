# 키움 REST API 연결: 환경 설정과 인증

## 목적과 현재 범위

스톡스푼의 모의투자에 사용할 실전 시장 데이터를 가져오기 위한 인증 기반이다.
실전 서버 `https://api.kiwoom.com`에 연결한다.
환경 설정, 접근 토큰 발급·재사용·갱신, 코스피 지수 조회와 FE 조회 API를 구현했다.
현재가 실시간 구독(0B)은 [WebSocket 구현 기록](stockspoon_kiwoom_websocket.md)을 참고한다.
개별 종목 REST 조회, 호가 구독, 자체 체결 엔진은 후속 작업이다.

## 1. 환경 설정

프로젝트 루트의 Git 제외 파일 `.env.local`에 다음 두 항목을 입력한다.
실제 값에는 따옴표를 붙이지 않는다. 이 파일은 Java properties 형식으로 읽는다.
키 문자열에 역슬래시가 포함되는 경우 환경변수로 직접 설정하는 것을 권장한다.

```properties
KIWOOM_APP_KEY=발급받은_앱키
KIWOOM_APP_SECRET=발급받은_시크릿
```

`application.yaml`의 `spring.config.import`가
`optional:file:.env.local[.properties]`를 읽는다.
따라서 프로젝트 루트를 작업 디렉터리로 실행해야 한다.
파일이 없는 배포 환경에서는 OS 환경변수로 같은 항목을 전달한다.
환경변수는 파일 값보다 우선한다. 키가 없어도 기존 기능은 시작할 수 있으나
토큰 요청 시 설정 누락 오류를 반환한다.
기존 MySQL 설정도 동일한 파일에서 읽는다.

키움 APP KEY 관리 페이지에서 API 요청이 나가는 공인 IP를 허용 IP로 등록해야 한다.
로컬 개발은 PC의 인터넷 회선 공인 IP, AWS는 서버의 외부 통신에 사용하는
공인 IP(예: NAT Gateway의 Elastic IP)를 기준으로 한다.
localhost나 사설 IP를 등록하는 것이 아니다.
현재 PC의 IP 등록 여부는 실제 인증으로 확인해야 한다.

## 2. 인증 흐름

1. 서버 내부 코드가 `KiwoomTokenProvider.accessToken()`을 호출한다.
2. 캐시 토큰이 있고 만료까지 60초 초과로 남으면 재사용한다.
3. 없거나 만료 1분 전이면 `POST /oauth2/token`을 호출한다.
4. JSON 본문에 `grant_type=client_credentials`, `appkey`, `secretkey`를 전달한다.
5. `return_code=0`, Bearer 타입, 토큰 및 만료 시간을 검증한다.
6. `expires_dt`를 `uuuuMMddHHmmss`, Asia/Seoul 기준으로 해석하여 저장한다.

갱신은 예약 작업이 아니라 다음 요청이 들어올 때 수행한다.
동기화로 한 프로세스 안에서 중복 발급을 방지한다.
서버 재시작 시 캐시는 사라진다. 여러 서버 간 토큰 공유는 아직 구현하지 않았다.
연결 제한 시간은 3초, 응답 읽기 제한 시간은 5초다. 키움 요청 본문은 버퍼링해 Content-Length를 설정한다.
실패한 발급 요청을 자동으로 반복하지 않으며, 다음 호출에서 다시 시도한다.
키·토큰·공급자 응답 본문은 로그나 예외 메시지에 출력하지 않는다.
토큰을 프론트엔드에 전달하는 HTTP API는 만들지 않았다.

## 3. 코스피 지수 첫 조회

`KiwoomMarketClient.kospi()`는 유효한 토큰을 받아
`POST https://api.kiwoom.com/api/dostk/sect`를 호출한다.
헤더에는 `api-id: ka20001`과 `Authorization: Bearer <token>`을 넣는다.
본문은 `{"mrkt_tp":"0","inds_cd":"001"}`이다.
`0`은 코스피 시장, `001`은 종합(KOSPI) 업종코드다.

`return_code=0` 응답에서 `cur_prc`(지수값), `pred_pre`(전일 대비),
`flu_rt`(등락률)를 `BigDecimal`로 변환한다.
`fetchedAt`은 백엔드가 응답을 읽은 시각이며 키움 시장 시각이 아니다.
키움 오류 응답이나 숫자 형식 오류는 인증값 및 공급자 응답 본문을 노출하지 않고 실패 처리한다.
FE 조회 API와 백엔드 메모리 보관 방식은 다음 절에 설명한다.

2026-09-26 실전 조회: 키움 토큰 발급 및 코스피 지수 조회 모두 `return_code=0`.
실제 응답의 세 숫자 필드를 Java 코드에서 읽는 테스트가 통과했다.
토요일 조회이므로 장중 실시간 갱신 여부는 검증하지 않았다.
가짜 응답 테스트에서는 요청 경로·헤더·본문, 부호 있는 숫자 변환과 실패 처리를 검증했다.

## 4. FE 코스피 지수 조회 API

`GET /api/v1/market/indices/kospi`는 요청 본문과 쿼리 파라미터가 없다.
기존 API 보안 정책에 따라 로그인이 필요하며, 브라우저는 `access_token` 쿠키를 전송한다.
FE 요청은 키움에 직접 연결하지 않고 백엔드가 마지막으로 성공한 값을 읽는다.

성공 응답 `200 OK` 예시(숫자는 테스트용 예시값):

```json
{
  "value": 2817.42,
  "change": -12.50,
  "changeRate": -0.44,
  "fetchedAt": "2026-09-26T06:30:00Z"
}
```

`changeRate`의 단위는 %이며 `-0.44`는 -0.44%를 뜻한다.
`fetchedAt`은 백엔드가 키움 응답을 읽은 시각(UTC)이다.
키움 시장의 체결 시각이나 해당 지수의 산출 시각은 아니다.
서버가 시작한 뒤 지수를 한 번도 가져오지 못했다면 `503 Service Unavailable`을 반환한다.

```json
{
  "code": "MARKET_DATA_UNAVAILABLE",
  "message": "코스피 지수를 아직 조회하지 못했습니다."
}
```

로그인하지 않은 요청은 기존 보안 정책에 따라 `401 Unauthorized`다.
키움 조회가 일시적으로 실패하더라도 이전 성공값을 유지하며,
FE는 `fetchedAt`으로 마지막 백엔드 조회 시각을 확인할 수 있다.

백엔드는 평일 09:00~15:40(Asia/Seoul)에 기본 10초 간격으로 갱신한다.
시장 휴일은 별도로 판별하지 않는다. 서버 시작 후 값이 없으면 장외에도 한 번 조회하고,
첫 조회가 실패하면 값을 얻을 때까지 장외에는 약 60초 간격으로 재시도한다.
`market.kospi.refresh-ms`로 갱신 간격을 조정할 수 있다.
테스트에서는 `market.kospi.refresh-enabled=false`로 자동 실전 호출을 막는다.
값은 서버 한 프로세스의 메모리에만 있으므로 서버 재시작 시 사라지고,
여러 서버 사이에는 아직 공유되지 않는다.

## 코드 위치

- `market/kiwoom/KiwoomConfig.java`: 키 설정과 실전 REST 클라이언트 구성
- `market/kiwoom/KiwoomTokenProvider.java`: 발급, 유효성 검사, 캐시 및 갱신
- `market/kiwoom/KiwoomMarketClient.java`: 코스피 지수 조회와 숫자 변환
- `market/KospiIndexService.java`: 주기적 갱신과 마지막 성공값 보관
- `market/KospiIndexController.java`: FE 조회 API
- `src/main/resources/application.yaml`: 로컬 설정 파일 연결과 키움 설정

## 검증

일반 테스트는 실제 키 없이 가짜 응답으로 실행한다. 실전 토큰·지수 테스트는 기본 실행에서 건너뛴다.

```powershell
.\gradlew.bat test --no-daemon
```

2026-09-26 자동 테스트: 전체 64개 중 62개 성공, 실전 호출 테스트 2개는 기본 실행에서 건너뜀.
재사용·갱신 경계, 한국 시간 변환, 동시 호출, 키 누락, 공급자 오류,
만료·잘못된 응답, HTTP 오류, 타임아웃 및 8050 단말기 인증 오류를 검증했다.

2026-09-27 자동 테스트: 전체 67개 중 65개 성공, 실전 호출 테스트 2개는 기본 실행에서 건너뜀.
FE 조회의 401·503·200 응답, 요청별 키움 중복 호출 방지, 갱신 실패 시 이전 값 유지,
한국 시간 기준 갱신 시간 경계를 검증했다.

2026-09-26 실전 인증: 첫 시도에서는 공인 출구 IP가 키움 허용 IP 목록에 없어
HTTP 200, `return_code=3`, 상세 코드 `8050`으로 거부되었다.
IP 등록 후 키움이 `return_code=0`과 토큰을 반환했다.
이어서 프로젝트 Java 클라이언트에서 응답 읽기 타임아웃이 발생했다.
본문 길이를 지정한 Java 요청은 성공했고, Spring RestClient의
`bufferContent`를 적용한 뒤 실전 토큰 발급·재사용 테스트가 통과했다.
키와 토큰 값은 출력하거나 기록하지 않았다.
아래 테스트는 DB나 사용자 로그인이 필요 없고 토큰 값을 출력하지 않는다.
환경변수 또는 프로젝트 루트의 `.env.local`에서 키를 읽는다.
실제 서버 호출을 명시적으로 활성화한 경우에만 실행한다.

```powershell
$env:KIWOOM_LIVE_TEST = 'true'
try {
    .\gradlew.bat test --tests '*KiwoomTokenLiveTests' --rerun-tasks --no-daemon
} finally {
    Remove-Item Env:KIWOOM_LIVE_TEST
}
```

기본 테스트 실행에서는 실전 토큰·지수 조회 테스트 각 1개가 건너뛰어진다.
실전 검증 결과에는 날짜와 성공 여부만 기록하며 키·토큰은 기록하지 않는다.

코스피 실전 조회만 다시 확인할 때:

```powershell
$env:KIWOOM_LIVE_TEST = 'true'
try {
    .\gradlew.bat test --tests '*KiwoomMarketLiveTests' --rerun-tasks --no-daemon
} finally {
    Remove-Item Env:KIWOOM_LIVE_TEST
}
```

## 공식 명세

- [키움 REST API 접근토큰발급](https://openapi.kiwoom.com/guide/apiguide)
- [키움 공식 코스피 지수 조회 예제](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/examples/%EA%B5%AD%EB%82%B4%EC%A3%BC%EC%8B%9D/%EC%97%85%EC%A2%85/get_domestic_sector_price.py)
