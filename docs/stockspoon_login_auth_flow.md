# 스톡스푼 로그인·인증 흐름 정리

> 목적: OAuth 로그인 정책, FE/BE 역할, 현재 구현 상태와 연동 방법을 한 곳에서 관리한다.

## 현재 구현 상태

현재 BE에는 다음 범위까지 구현되어 있다.

```text
FE가 전달한 카카오 인가코드 수신
→ 카카오 Access Token 발급 요청
→ 카카오 사용자 정보 조회
→ provider_user_id와 nickname 확인
→ nickname 필수 검증
→ H2 DB에서 기존 회원 조회
→ 신규 회원 생성 또는 기존 회원 nickname 동기화
```

아직 구현되지 않은 범위:

- StockSpoon Access JWT / Refresh JWT 발급
- JWT의 HttpOnly Cookie 저장
- 토큰 재발급과 로그아웃

현재 `KAKAO_AUTH_VERIFIED` 응답은 **카카오 인증 확인과 회원 조회·가입 완료**를 뜻한다. 아직 스톡스푼 JWT를 발급하지 않으므로 스톡스푼 로그인 전체가 완료된 상태는 아니다.

## 1. 현재 확정된 인증 정책

- OAuth 로그인 제공자: **Kakao**
- v3까지 사용자와 OAuth 계정 관계: **1:1**
- 카카오 회원번호(`id`)를 사용자 식별값으로 사용
- 카카오 로그인 동의 항목에서 **닉네임을 필수 동의**로 설정
- 카카오 로그인 동의 항목에서 **프로필 이미지를 선택 동의**로 설정
- 회원가입 화면에서 닉네임을 따로 입력받지 않고 **카카오 닉네임을 그대로 사용**
- 카카오 사용자 정보에 닉네임이 없거나 빈 값이면 **회원가입과 로그인을 실패 처리**
- 기존 사용자가 다시 로그인할 때마다 최신 카카오 닉네임을 `users.nickname`에 저장
- 인증 방식: **토큰 인증**
- 서비스 자체 토큰:
  - **Access Token**
  - **Refresh Token**
- 두 토큰 모두 **JWT**
- 두 토큰 모두 브라우저의 **HttpOnly Cookie**에 저장
- 이후 요청에서는 브라우저가 Cookie 헤더를 통해 토큰을 자동 전송
- 서버는 Access Token의:
  - 서명 검증
  - 만료 시간 검증
  - 토큰에서 사용자 식별자 추출
  - 해당 사용자의 리소스 접근 권한 검증
- 최초 로그인 이후 일반 서비스 API는 Access Token 인증을 전제로 한다.
- 단, 인증 전 단계인 **카카오 로그인 처리 API**와 **Access Token 재발급 API**는 Access Token 없이 호출 가능해야 한다.

---

## 2. FE / BE 역할 분담

### FE 담당

FE는 **카카오 인가코드(Authorization Code)를 받고 OAuth `state`를 검증하는 부분까지** 담당한다.

흐름:

1. 사용자가 FE에서 `카카오 로그인` 버튼 클릭
2. FE가 `crypto.getRandomValues` 또는 `crypto.randomUUID`로 예측하기 어려운 `state` 생성
3. FE가 같은 탭의 `sessionStorage`에 `state`와 생성 시각 저장
4. `response_type`, `client_id`, `redirect_uri`, `state`를 포함한 카카오 인가 URL로 브라우저 이동
5. 사용자가 카카오 로그인 및 정보 제공에 동의
6. 카카오 인증 서버가 설정된 FE Redirect URI로 `code`와 `state`를 전달
7. FE가 저장한 `state`와 반환된 `state`의 존재 여부, 일치 여부, 유효기간을 검사
8. 검증 성공·실패와 관계없이 사용한 저장값과 URL의 `code`, `state` 제거
9. 검증에 성공한 경우에만 인가코드를 BE 로그인 API에 전달

개념 예시:

```text
사용자
  ↓
FE 카카오 로그인 버튼
  ↓
Kakao 로그인/동의 화면
  ↓
Kakao → FE Redirect URI
  ?code={authorization_code}&state={state}
  ↓
FE가 state 검증
  ↓ 검증 성공
FE → BE
authorization_code 전달
```

`GET https://kauth.kakao.com/oauth/authorize`는 `fetch`로 인가코드 JSON을 받는 요청이 아니다. FE가 브라우저를 해당 URL로 이동시키고, 카카오는 로그인·동의 후 FE Redirect URI로 다시 이동시킨다.

`state`는 사용자를 식별하는 값이 아니라, 돌아온 로그인 결과가 같은 브라우저 탭에서 시작한 요청과 연결되는지 확인하는 일회용 값이다. 같은 탭·같은 FE 출처로 돌아오는 흐름을 전제로 하며, 팝업이나 다른 탭을 사용할 경우 별도 합의가 필요하다.

다음 상황에서는 BE 로그인 API를 호출하지 않고 새 로그인을 시작한다.

- 저장한 `state` 또는 반환된 `state`가 없음
- 두 `state`가 일치하지 않음
- 생성 후 5분 이상 지남
- 카카오 로그인이 취소되거나 오류가 반환됨
- 같은 콜백이 중복 처리됨

---

### BE 담당

BE는 **FE로부터 인가코드를 받은 이후부터** 담당한다.

흐름:

1. FE로부터 카카오 인가코드 수신
2. BE가 인가코드를 사용하여 **Kakao Token API** 호출
3. Kakao Access Token 발급
4. Kakao Access Token을 사용하여 **카카오 사용자 정보 API** 호출
5. 카카오 사용자 식별값(`provider_user_id`)과 닉네임 확인
6. 기존 가입 사용자 여부 조회
7. 닉네임이 없거나 빈 값이면 로그인 실패 처리
8. 기존 사용자라면 `users.nickname`을 최신 카카오 닉네임으로 갱신
9. 최초 사용자라면 카카오 닉네임으로 사용자 정보 생성
10. BE가 **스톡스푼 자체 Access JWT / Refresh JWT 발급**
11. 두 JWT를 **HttpOnly Cookie**로 응답
12. 이후 스톡스푼 API는 스톡스푼 Access JWT로 인증

개념 흐름:

```text
FE
  ↓
인가코드 전달
  ↓
BE
  ↓
Kakao Token API
  ↓
Kakao Access Token 획득
  ↓
Kakao 사용자 정보 API
  ↓
provider_user_id와 nickname 확인
  ↓
닉네임 필수 검증
  ↓
기존 회원 닉네임 동기화 / 신규 회원 생성
  ↓
StockSpoon Access JWT 생성
StockSpoon Refresh JWT 생성
  ↓
Set-Cookie
  ↓
이후 StockSpoon API 인증
```

OAuth `state`는 인가코드 요청과 FE 콜백 검증에만 사용한다. BE가 인가코드로 카카오 토큰을 요청할 때는 `state`를 보내지 않는다.

---

## 3. 카카오 토큰과 스톡스푼 토큰 구분

두 종류의 토큰은 역할이 완전히 다르다.

### Kakao Access Token

용도:

- 카카오 API 호출
- 카카오 사용자 정보 조회
- 카카오 사용자 식별

예:

```text
Kakao Access Token
→ Kakao 사용자 정보 조회 API
→ provider_user_id 획득
```

이 토큰을 스톡스푼의 주문, 계좌, 대결 등 일반 API 인증에 사용하지 않는다.

---

### StockSpoon Access JWT

용도:

- 스톡스푼 서비스 API 인증
- 서버에서 현재 사용자 식별
- 사용자별 리소스 접근 권한 확인

예:

```http
POST /api/v1/orders
Cookie: access_token={StockSpoon Access JWT}
```

BE는 스톡스푼 Access JWT를 검증한 뒤 사용자 ID를 추출한다.

---

### StockSpoon Refresh JWT

용도:

- 스톡스푼 Access JWT가 만료됐을 때 새 Access JWT 발급

Refresh Token 역시 HttpOnly Cookie로 전달한다.

---

## 4. 회원 조회·가입과 닉네임 처리

카카오 사용자 식별값을 기준으로 기존 회원인지 확인하고, 카카오 닉네임을 사용자 닉네임으로 사용한다.

```text
Kakao provider_user_id와 nickname 조회

nickname 없음 또는 빈 값
→ 회원가입/로그인 실패

기존 OAuth 사용자 존재
→ users.nickname을 현재 Kakao nickname으로 갱신
→ 기존 user로 로그인

기존 OAuth 사용자 없음
→ Kakao nickname으로 users 생성
→ user_oauth 생성
→ 로그인 처리
```

회원가입 화면에서 닉네임을 추가로 입력받지 않는다. 카카오에서 받은 닉네임을 별도로 변경하지 않고 저장한다. 카카오 닉네임이 변경되면 다음 로그인에서 `users.nickname`도 변경된다. 닉네임 중복 허용 여부는 별도 정책과 DB 제약조건으로 확정해야 한다.

최초 회원 생성 시 `users`와 `user_oauth` 생성은 하나의 DB 트랜잭션에서 수행한다. 기존 사용자의 닉네임 갱신도 로그인 처리 트랜잭션에 포함한다. 중간에 실패하면 일부 데이터만 저장되지 않도록 전체 작업을 되돌린다.

---

## 5. OAuth 사용자 정책

v3까지는 다음과 같이 운영한다.

```text
OAuth Provider
= Kakao only

users : user_oauth
= 1 : 1
```

현재 `user_oauth`는 별도 `oauth_id` PK를 유지한다.

개념 구조:

```text
users

- user_id
- nickname

user_oauth

- oauth_id
- user_id
- provider
- provider_user_id
- created_at
```

`provider` 컬럼은 유지하지만 v3까지 실제 값은 `kakao`만 사용한다.

같은 카카오 계정이 여러 사용자로 중복 가입되지 않도록 `(provider, provider_user_id)`는 유일하게 식별되어야 한다.

---

## 6. 인증이 필요한 API

로그인 완료 이후 일반적인 스톡스푼 서비스 API는 Access Token 인증을 요구한다.

예:

```text
/accounts/**
/orders/**
/competitions/**
/users/**
...
```

서버 처리:

```text
요청 수신
↓
Access JWT 쿠키 확인
↓
서명 검증
↓
만료 검증
↓
user_id 추출
↓
요청 리소스의 접근 권한 확인
↓
API 처리
```

인증 없이 접근 가능한 API는 최소한 다음과 같은 인증 흐름 관련 API가 된다.

```text
- 카카오 로그인 처리 API
- Access Token 재발급 API
```

현재 인증 관련 URI는 다음과 같다.

```text
GET  /api/v1/auth/csrf
POST /api/v1/auth/login
```

기존에 구현했던 `POST /api/v1/auth/kakao/prepare`는 FE가 `state`를 관리하기로 확정하면서 제거했다. BE는 OAuth 로그인 URL이나 `state`를 생성·저장하지 않는다.

---

## 7. 회원 탈퇴 관련 현재 결정

회원 탈퇴는 **Hard Delete** 방식으로 처리한다.

즉:

```text
회원 탈퇴
→ users 실제 삭제
```

다만 다음 사항은 아직 별도 정책 확정이 필요하다.

- `user_oauth` 연쇄 삭제 여부 및 FK 정책
- `account_owner` 삭제 방식
- 일반 계좌 / 대결 전용 계좌 / 주문 / 체결 / 대결 이력 처리
- 카카오 연결 해제(Unlink)까지 수행할지 여부

회원 탈퇴의 구체적인 연쇄 삭제 범위는 아직 확정하지 않는다.

---

## 8. 아직 결정되지 않은 인증 항목

아래 항목은 후속 결정이 필요하다.

- StockSpoon Access Token 만료 시간
- StockSpoon Refresh Token 만료 시간
- Refresh Token 재발급/회전(Rotation) 정책
- 로그아웃 시 Refresh Token 폐기 방식
- Refresh Token 서버 저장 여부
- Cookie의 `Secure`, `SameSite`, `Path`, `Domain` 값
- CSRF 방어 방식
- Kakao Access/Refresh Token을 로그인 이후 저장할지 여부
- 카카오 연결 해제와 스톡스푼 회원 탈퇴의 관계
- 중복 로그인 / 다중 기기 로그인 정책
- Access Token 만료 시 FE의 재발급 호출 방식
- 로그인 실패 및 OAuth 오류 응답 규격

---

## 9. 현재 로그인 흐름 한 줄 요약

```text
FE가 Kakao 로그인 및 인가코드 수신
→ 인가코드를 BE에 전달
→ BE가 Kakao 토큰 발급
→ Kakao 사용자 정보 조회
→ 카카오 닉네임 필수 검증
→ 기존 회원 닉네임 동기화 또는 신규 회원 생성
→ BE가 StockSpoon Access/Refresh JWT 발급
→ HttpOnly Cookie 저장
→ 이후 서비스 API는 StockSpoon Access JWT로 인증
```

---

## 10. 구현 시 가장 중요한 구분

```text
Kakao Access Token
= Kakao API 호출용

StockSpoon Access JWT
= StockSpoon API 인증용
```

카카오 로그인 성공으로 얻은 Kakao Access Token을 스톡스푼 자체 API 인증 토큰으로 사용하지 않는다.

---

## 11. 현재 FE → BE 호출 계약

FE는 BE 요청에 쿠키가 포함되도록 `credentials: 'include'`를 사용한다.

### 1) CSRF 확인값 발급

```http
GET /api/v1/auth/csrf
```

응답 예시:

```json
{
  "token": "csrf-token",
  "header_name": "X-XSRF-TOKEN"
}
```

브라우저는 HttpOnly `XSRF-TOKEN` 쿠키를 저장하고, FE는 응답 본문의 `token`을 다음 요청 헤더에 넣는다. OAuth `state`와 CSRF 토큰은 목적이 다르므로 둘 다 필요하다.

- OAuth `state`: 카카오에서 FE로 돌아온 콜백 검증
- CSRF 토큰: FE에서 BE로 보내는 로그인 요청 보호

### 2) 인가코드 전달

```http
POST /api/v1/auth/login
Content-Type: application/json
X-XSRF-TOKEN: {csrf-token}
```

```json
{
  "authorization_code": "카카오에서 받은 인가코드"
}
```

FE는 OAuth `state`를 BE에 전달하지 않는다. BE는 FE가 `state`를 검증했는지 직접 확인하지 않으며, CSRF 검증은 별도로 수행한다.

현재 성공 응답:

```json
{
  "code": "KAKAO_AUTH_VERIFIED",
  "message": "카카오 인증이 확인되었습니다."
}
```

인가코드는 일회용이므로 교환 실패 후 같은 코드를 자동 재시도하지 않고 카카오 로그인을 처음부터 다시 시작한다.

---

## 12. 현재 BE 내부 처리

관련 코드의 역할:

1. `KakaoLoginRequest`: `authorization_code` 입력 형식과 필수값 검증
2. `AuthController`: CSRF 확인값 발급, 로그인 요청 수신
3. `KakaoAuthService`: 서버 설정 확인 후 `KakaoClient` 호출
4. `KakaoClient`: 카카오 토큰 API와 사용자 정보 API 호출
5. `SecurityConfig`: CSRF, CORS, API 접근 규칙과 외부 요청 제한 시간 설정

실제 처리 순서:

```text
POST /api/v1/auth/login
→ Spring Security의 CORS·CSRF 검사
→ authorization_code 입력 검증
→ KakaoAuthService.verify()
→ POST https://kauth.kakao.com/oauth/token
→ GET https://kapi.kakao.com/v2/user/me
→ provider_user_id와 nickname 반환
→ nickname 필수 검증
→ H2 DB에서 기존 OAuth 회원 조회
→ 기존 회원 nickname 갱신 또는 신규 users/user_oauth 생성
```

현재 코드는 `KakaoClient`가 `id`와 `kakao_account.profile.nickname`을 반환하고, 서비스가 회원 조회·가입과 닉네임 동기화를 수행하는 단계까지 구현되어 있다.

### H2 기반 DB 구현 내용

개발 단계에서는 데이터를 보존할 필요가 없으므로 인메모리 H2를 사용한다.

1. 카카오 개발자 콘솔의 로그인 동의 항목에서 닉네임을 필수 동의로 설정한다.
2. Spring Data JPA와 H2 의존성을 사용한다.
3. `User`, `UserOAuth`, `OAuthProvider`로 회원과 카카오 계정을 표현한다.
4. `(provider, provider_user_id)`에 유일 제약조건을 적용한다.
5. `KakaoClient`가 사용자 정보 응답의 `id`와 `kakao_account.profile.nickname`을 읽는다.
6. 닉네임이 없거나 빈 값이면 DB를 변경하지 않고 `KAKAO_NICKNAME_NOT_PROVIDED` 오류를 반환한다.
7. `UserOAuthRepository`로 카카오 사용자의 가입 여부를 조회한다.
8. 가입되지 않았다면 카카오 닉네임으로 `users`를 만들고 `user_oauth`를 연결한다.
9. 가입되어 있다면 카카오 닉네임을 기존 `users.nickname`에 저장한다.
10. H2를 사용하는 테스트로 신규 가입, 기존 회원 닉네임 동기화와 닉네임 누락을 검증한다.

나중에 MySQL로 바꿀 때는 DataSource와 JPA 방언 등 환경 설정과 DB 스키마 생성 방식을 변경한다. 서비스, 엔티티, 저장소 인터페이스는 최대한 그대로 유지한다.

카카오 토큰 요청에는 다음 값을 사용한다.

```text
grant_type=authorization_code
client_id={KAKAO_CLIENT_ID}
redirect_uri={KAKAO_REDIRECT_URI}
code={authorization_code}
client_secret={KAKAO_CLIENT_SECRET}  // 활성화한 경우
```

카카오 Access Token은 사용자 정보 API가 요구하는 Bearer 방식으로 BE와 카카오 서버 사이에서만 사용한다. FE에 반환하거나 DB에 저장하지 않으며, HTTP 본문과 Authorization 헤더를 로그로 남기지 않는다.

---

## 13. 실행 환경 설정

IntelliJ 실행 구성의 환경변수로 설정하며 비밀값은 Git에 커밋하지 않는다.

| 환경변수 | 의미 |
|---|---|
| `KAKAO_CLIENT_ID` | FE 인가 요청과 같은 카카오 앱의 REST API 키 |
| `KAKAO_CLIENT_SECRET` | 카카오 토큰 요청용 비밀키. 활성화한 경우 필수이며 BE에만 보관 |
| `KAKAO_REDIRECT_URI` | 카카오에 등록한 FE 콜백 주소. FE 인가 요청의 값과 정확히 같아야 함 |
| `FRONTEND_ORIGIN` | 허용할 FE 출처. 예: `http://localhost:3000` |
| `AUTH_COOKIE_SECURE` | 운영 HTTPS에서는 `true`, 로컬 HTTP 개발에서만 `false` |

BE는 FE가 전달한 임의의 Redirect URI를 사용하지 않고 서버 환경변수의 값을 사용한다. 카카오 설정이 없거나 Redirect URI가 잘못되면 로그인 요청에 `OAUTH_NOT_CONFIGURED`를 반환한다.

현재 `SameSite=Lax` 설정은 FE와 BE가 같은 사이트인 구성을 전제로 한다. 서로 다른 사이트에 배포하기 전에 쿠키 정책과 브라우저의 제3자 쿠키 제한을 다시 확인한다.

---

## 14. 현재 오류 응답

| HTTP 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 인가코드 누락, 빈 값 또는 요청 형식 오류 |
| 400 | `INVALID_AUTHORIZATION_CODE` | 만료되거나 이미 사용한 인가코드 |
| 403 | `INVALID_CSRF_TOKEN` | CSRF 쿠키·헤더 누락 또는 불일치 |
| 500 | `OAUTH_CONFIGURATION_ERROR` | 카카오가 클라이언트 설정을 거절함 |
| 502 | `KAKAO_NICKNAME_NOT_PROVIDED` | 카카오 사용자 정보에 닉네임이 없거나 빈 값임 |
| 502 | `OAUTH_PROVIDER_ERROR` | 카카오 연결 실패 또는 비정상 응답 |
| 503 | `OAUTH_NOT_CONFIGURED` | 카카오 앱 키·Redirect URI 미설정 또는 잘못된 URI |
| 504 | `OAUTH_PROVIDER_TIMEOUT` | 카카오 서버 응답 시간 초과 |

OAuth `state` 오류는 FE에서 처리하므로 BE는 `INVALID_OAUTH_STATE`를 반환하지 않는다.

---

## 15. 확인 방법

프로젝트 폴더에서 다음 명령을 실행한다.

```powershell
.\gradlew.bat test
```

- `.\gradlew.bat`: 프로젝트에 포함된 Windows용 Gradle 실행기
- `test`: 자동 테스트 실행 작업
- 의미: 프로젝트의 자동 테스트를 실행한다.

BE 테스트에서는 실제 카카오 서버 대신 모의 응답을 사용해 다음 내용을 확인한다.

- 인가코드만 전달한 정상 요청 처리
- CSRF 누락·불일치 차단
- 입력값 검증
- CORS 허용·거부
- 제거된 `/kakao/prepare` API가 열려 있지 않음
- 카카오 오류와 시간 초과 처리

FE에서는 `state` 일치·불일치·누락·만료·재사용, 로그인 동의 취소와 콜백 중복 처리를 별도로 테스트한다. 실제 카카오 연동은 앱 키와 FE 주소가 정해진 후 브라우저에서 확인한다.
