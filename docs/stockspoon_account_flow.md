# 스톡스푼 v1 계좌 구현 흐름

> DB 관련 H2 설명은 계좌 기능 개발 당시 상태다. 현재 실행 DB와 전환 절차는 [MySQL 전환 기록](stockspoon_mysql_setup.md)을 참고한다.

> 기준: `feat/5-account`의 현재 코드. 이 문서는 계좌 개발 과정에서 결정한 내용과 실제 구현 상태를 함께 기록한다. 계좌·보유종목 정책 정의서는 정책 참고 자료이며, 구현 여부는 아래에서 별도로 표시한다.

## 1. 현재 구현 범위

카카오 로그인으로 스톡스푼 사용자가 만들어진 뒤 다음 기능을 사용할 수 있다.

| 단계 | API | 결과 |
|---|---|---|
| 최초 온보딩 | `POST /api/v1/users/me/onboarding` | 시작 자금으로 `기본 계좌`를 만들고 `users.onboarding_completed=true` |
| 추가 계좌 생성 | `POST /api/v1/users/me/accounts` | 완료된 사용자에게 계좌 추가 |
| 계좌 목록 | `GET /api/v1/users/me/accounts` | 내 활성 계좌 목록 |
| 계좌 상세 | `GET /api/v1/users/me/accounts/{accountId}` | 내 활성 계좌 한 개 |
| 계좌명 수정 | `PATCH /api/v1/users/me/accounts/{accountId}` | 내 활성 계좌의 이름 변경 |

계좌 비활성화, 보유종목, 주문·체결, 시세, 총자산·손익·수익률 계산은 아직 구현되지 않았다. 모든 API는 스톡스푼 `access_token` 쿠키 인증을 사용한다. `POST`와 `PATCH`는 CSRF 쿠키와 `X-XSRF-TOKEN` 헤더도 필요하다. FE는 쿠키 전송을 위해 `credentials: 'include'`를 사용한다. 인증·CSRF 발급 방법은 [로그인·인증 흐름](stockspoon_login_auth_flow.md)을 참고한다.

## 2. 온보딩: 첫 계좌 만들기

카카오 로그인만 완료한 신규 사용자는 `onboarding_completed=false`다. FE가 시작 자금을 받아 다음 요청을 보낸다.

```http
POST /api/v1/users/me/onboarding
Content-Type: application/json
X-XSRF-TOKEN: {csrf-token}
Cookie: access_token={StockSpoon Access JWT}; XSRF-TOKEN={csrf-token}

{"initial_capital": 10000000}
```

처리 순서:

1. `OnboardingController`가 JWT `sub`에서 사용자 ID를 읽는다.
2. `OnboardingRequest`가 시작 자금 1,000,000~100,000,000원을 검증한다.
3. `AccountService.onboard()`가 사용자를 조회하고 온보딩 완료 여부 및 기존 계좌 존재 여부를 확인한다.
4. 이름이 정확히 `기본 계좌`인 활성 계좌를 만든다. 초기 현금은 시작 자금과 같고 `ai_delegated=true`다.
5. 같은 트랜잭션에서 `User.completeOnboarding()`을 호출한다. 중간에 실패하면 계좌 생성과 완료 상태 변경이 함께 되돌아간다.
6. `201 Created`와 계좌 정보를 반환한다.

이미 온보딩을 완료했거나 계좌가 있으면 `409 ONBOARDING_ALREADY_COMPLETED`다. 첫 계좌명은 요청으로 받지 않는다. 첫 계좌도 생성 이후 이름을 수정할 수 있다.

## 3. 온보딩 이후 계좌 추가

```http
POST /api/v1/users/me/accounts
Content-Type: application/json
X-XSRF-TOKEN: {csrf-token}
Cookie: access_token={StockSpoon Access JWT}; XSRF-TOKEN={csrf-token}

{"account_name": "장기 투자", "initial_capital": 5000000}
```

`AccountService.create()`는 `onboarding_completed=true`인지 확인한다. 완료 전이면 `409 ONBOARDING_REQUIRED`다. 시작 자금 범위는 첫 계좌와 같다. 계좌 개수 상한은 없다. `account_name`이 없거나 공백만 있으면 현재 활성 계좌 이름과 충돌하지 않는 가장 작은 번호를 찾아 `기본 계좌 1`, `기본 계좌 2`처럼 붙인다.

사용자가 입력한 이름은 앞뒤 공백을 제거하고 저장한다. 최대 20자이며, 같은 사용자의 활성 계좌 이름과 영문 대소문자만 다를 때도 중복으로 취급한다. 초기 현금은 시작 자금과 동일하고 AI 위임은 항상 ON이다. 시작 자금과 AI 위임 상태를 변경하는 API는 없다.

두 생성 API는 역할이 다르지만 같은 `Account` 엔티티를 `accounts` 테이블에 저장한다.

## 4. 계좌 목록에서 선택하고 상세 보기

```http
GET /api/v1/users/me/accounts
Cookie: access_token={StockSpoon Access JWT}
```

`AccountService.list()`는 JWT 사용자 ID로 활성 계좌만 조회한다. 정렬은 `created_at` 오름차순, 같은 시각이면 `account_id` 오름차순이다. 계좌가 없으면 `200 OK`와 `[]`를 반환한다. 다른 사용자의 계좌는 목록에 포함되지 않는다.

FE가 목록에서 계좌를 선택하면 해당 `account_id`를 보관하고 상세 조회에 사용한다. 현재 선택된 계좌 ID는 BE DB에 저장하지 않는다.

```http
GET /api/v1/users/me/accounts/2
Cookie: access_token={StockSpoon Access JWT}
```

상세 조회는 `account_id + JWT 사용자 ID + is_active=true`를 모두 만족하는 계좌만 반환한다. 없는 계좌, 다른 사용자의 계좌, 비활성 계좌는 똑같이 `404 ACCOUNT_NOT_FOUND`다. 계좌 ID만으로 소유권을 판단하지 않는다.

목록·상세·생성·이름 수정은 현재 모두 다음 `AccountResponse` 형식을 사용한다. 목록은 이 객체의 배열이다.

```json
{
  "account_id": 2,
  "account_name": "장기 투자",
  "initial_capital": 5000000,
  "cash_balance": 5000000,
  "ai_delegated": true
}
```

보유종목과 시세가 없으므로 총자산과 수익률은 현재 응답에 포함하지 않는다. `cash_balance`는 계좌 생성 시 시작 자금으로 초기화되며, 매매에 따른 잔액 갱신은 아직 없다.

## 5. 계좌명 수정

```http
PATCH /api/v1/users/me/accounts/2
Content-Type: application/json
X-XSRF-TOKEN: {csrf-token}
Cookie: access_token={StockSpoon Access JWT}; XSRF-TOKEN={csrf-token}

{"account_name": "안정형 투자"}
```

`AccountService.rename()`은 먼저 내 활성 계좌를 찾고, 이름의 앞뒤 공백을 제거한다. 이름이 없거나 공백뿐이거나 20자를 넘으면 `400 INVALID_ACCOUNT_NAME`이다. 다른 활성 계좌의 이름과 중복되면 `409 DUPLICATE_ACCOUNT_NAME`이다. 현재 이름과 완전히 같으면 변경 없이 `200 OK`로 현재 계좌 정보를 돌려준다. 다른 사용자의 계좌나 비활성 계좌는 `404 ACCOUNT_NOT_FOUND`다. 시작 자금·현금·AI 위임 상태는 바꾸지 않는다.

## 6. 코드 위치와 역할

| 파일/패키지 | 역할 |
|---|---|
| `account/controller/OnboardingController.java` | 최초 계좌 생성 요청 수신 |
| `account/controller/AccountController.java` | 추가 생성, 목록, 상세, 이름 수정 요청 수신 |
| `account/dto/OnboardingRequest.java` | 첫 계좌의 `initial_capital` 입력·범위 검증 |
| `account/dto/AccountCreateRequest.java` | 추가 계좌의 이름·시작 자금 입력 |
| `account/dto/AccountNameUpdateRequest.java` | 수정할 `account_name` 입력 |
| `account/dto/AccountResponse.java` | 계좌 API의 공통 응답 |
| `account/service/AccountService.java` | 온보딩 조건, 기본 이름, 중복·소유권·활성 상태 확인, 트랜잭션 처리 |
| `account/repository/AccountRepository.java` | 사용자별 활성 계좌 조회 및 이름 중복 검사 |
| `account/entity/Account.java` | 계좌 소유자, 이름, 시작 자금, 현금, AI 위임, 활성 상태와 생성·수정 시각 저장 |
| `account/exception/AccountException*.java` | 계좌 오류를 HTTP 상태·오류 코드로 변환 |
| `user/entity/User.java` | `onboarding_completed` 저장 |
| `config/SecurityConfig.java` | JWT 쿠키 인증, CSRF, `PATCH`를 포함한 CORS 허용 |

현재 개발 DB는 인메모리 H2이고 JPA가 실행 시 스키마를 만들고 종료 시 제거한다. MySQL 운영 전환과 데이터 이전은 이 구현에 포함되지 않는다.

## 7. 오류와 확인 방법

| 상태 | 코드 | 조건 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 시작 자금 누락·범위 밖 등 요청 검증 실패 |
| 400 | `INVALID_ACCOUNT_NAME` | 이름 수정값이 없거나 공백뿐이거나 20자 초과 |
| 401 | `UNAUTHORIZED` | Access JWT 누락·무효 |
| 403 | `INVALID_CSRF_TOKEN` | 변경 요청의 CSRF 확인 실패 |
| 404 | `USER_NOT_FOUND` | JWT 사용자 ID에 해당하는 사용자 없음 |
| 404 | `ACCOUNT_NOT_FOUND` | 계좌 없음·타인 소유·비활성 계좌 |
| 409 | `ONBOARDING_ALREADY_COMPLETED` | 온보딩 중복 호출 또는 기존 계좌 존재 |
| 409 | `ONBOARDING_REQUIRED` | 온보딩 전에 추가 계좌 생성 |
| 409 | `DUPLICATE_ACCOUNT_NAME` | 활성 계좌 이름 중복 |

프로젝트 폴더에서 `.\gradlew.bat test`로 테스트한다. `AccountControllerTests`는 실제 H2 저장, JWT 쿠키, CSRF를 거쳐 온보딩, 이름 자동 생성·수정, 내 계좌만 조회, 미인증·중복·범위 오류를 확인한다. 테스트는 카카오 서버나 실제 증권 API를 호출하지 않는다.

## 8. 남은 정책·구현 간 차이

- 정책은 **비활성 계좌 이름 재사용**을 허용하지만, 현재 DB의 `(user_id, name)` 유일 제약은 비활성 계좌도 포함한다. 서비스의 중복 조회는 활성 계좌만 대상으로 한다. 비활성화 기능을 추가할 때 DB 제약도 함께 변경해야 한다.
- `is_active`는 생성 시 `true`로 설정되지만 비활성화 API는 없다. 보유주식 0주, 미체결 주문 0건 조건을 검사하려면 보유종목·주문 기능이 필요하다.
- 계좌 생성·이름 수정의 사전 중복 검사는 동시 요청의 경합까지 보장하지 않는다. 특히 MySQL 이전 시 유일 제약, 예외 변환, 자동 이름 생성의 동시성을 설계해야 한다.
- 총자산, 수익률, 주문 가능 현금, 보유수량, 주식분할·병합 처리는 보유종목·주문·체결·시세 개발 단계에서 구현해야 한다.
- 마지막 활성 계좌 비활성화 허용 여부, 활성 계좌 0개일 때 온보딩 상태, 병합 단주, 회원 탈퇴 시 이력 보존 범위는 아직 정책이 확정되지 않았다.
