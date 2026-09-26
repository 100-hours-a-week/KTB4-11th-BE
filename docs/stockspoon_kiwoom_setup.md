# 키움 REST API 연결: 환경 설정과 인증

## 목적과 현재 범위

스톡스푼의 모의투자에 사용할 실전 시장 데이터를 가져오기 위한 인증 기반이다.
실전 서버 `https://api.kiwoom.com`에 연결한다.
이번 단계에서는 환경 설정과 접근 토큰 발급·재사용·갱신을 구현했다.
종목 조회, 호가 조회, 실시간 구독, 자체 체결 엔진은 후속 작업이다.

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
연결 제한 시간은 3초, 응답 읽기 제한 시간은 5초다.
실패한 발급 요청을 자동으로 반복하지 않으며, 다음 호출에서 다시 시도한다.
키·토큰·공급자 응답 본문은 로그나 예외 메시지에 출력하지 않는다.
토큰을 프론트엔드에 전달하는 HTTP API는 만들지 않았다.

## 코드 위치

- `market/kiwoom/KiwoomConfig.java`: 키 설정과 실전 REST 클라이언트 구성
- `market/kiwoom/KiwoomTokenProvider.java`: 발급, 유효성 검사, 캐시 및 갱신
- `src/main/resources/application.yaml`: 로컬 설정 파일 연결과 키움 설정

## 검증

일반 테스트는 실제 키 없이 가짜 응답으로 실행한다.

```powershell
.\gradlew.bat test --no-daemon
```

2026-09-26 검증: 최초 신규 토큰 테스트 6개와 기존 테스트 53개, 총 59개 성공. 이후 8050 오류 구분 테스트를 추가했다.
재사용·갱신 경계, 한국 시간 변환, 동시 호출, 키 누락, 공급자 오류,
만료·잘못된 응답, HTTP 오류 및 타임아웃을 검증했다.

2026-09-26 실전 인증 시도: 키는 로컬 파일에 입력했지만 토큰 발급은 실패했다.
진단 요청에서 HTTP 200, `return_code=3`, 상세 코드 `8050`(단말기 인증 거부)을 확인했다.
당시 요청의 공인 출구 IP가 키움 허용 IP 목록에 등록되어 있지 않았다.
허용 IP 등록 후 같은 네트워크에서 다시 검증해야 한다.
실전 인증 성공은 아직 확인되지 않았다.
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

기본 테스트 실행에서는 실전 확인 테스트 1개가 건너뛰어진다.
실전 확인 성공 후에는 이 문서에 검증 날짜와 결과만 추가하고 키·토큰은 기록하지 않는다.

## 공식 명세

[키움 REST API 접근토큰발급](https://openapi.kiwoom.com/guide/apiguide)