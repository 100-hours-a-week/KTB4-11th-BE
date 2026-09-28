# 내 정보 조회 구현 (`feat/21-user-me`)

## 1. 로그인으로 저장되는 정보

카카오 로그인 과정에서 BE는 인가코드를 카카오 토큰으로 교환하고, 그 토큰으로 사용자 정보 API를 호출해 카카오 ID, 닉네임, 프로필 이미지 URL을 받는다. 토큰 응답과 사용자 정보 응답은 별도 요청의 결과다.

| 저장 위치 | 저장 내용 | 역할 |
|---|---|---|
| `users.user_id` | 스톡스푼에서 생성한 사용자 ID | 서비스 내부 사용자 식별 및 스톡스푼 JWT subject |
| `users.nickname` | 카카오 닉네임 | 화면에 표시할 이름 |
| `users.profile_image_url` | 카카오 프로필 이미지 URL 또는 `null` | 이미지 파일 자체가 아닌 주소 저장 |
| `user_oauth.provider`, `provider_user_id` | `KAKAO`, 카카오 사용자 ID | 재로그인 시 같은 카카오 계정인지 조회 |
| `user_oauth.user_id` | 연결된 스톡스푼 사용자 ID | 카카오 계정과 `users` 연결 |

`KakaoAuthService.verify()`는 카카오 ID로 가입 여부를 확인한다. 신규 사용자면 `User`와 `UserOAuth`를 저장하고, 기존 사용자면 `User.synchronizeProfile()`로 닉네임과 이미지 URL을 갱신한다. 닉네임이 없으면 로그인이 실패하고, 선택 항목인 이미지가 없으면 `null`로 저장한다.

카카오 ID와 스톡스푼 사용자 ID는 서로 다른 값이다. 내 정보 조회에는 스톡스푼 사용자 ID를 사용한다.

## 2. 요청과 응답

`GET /api/v1/users/me`는 로그인 쿠키(`access_token`)로 인증된 사용자의 DB 프로필을 반환한다. 요청 body와 사용자 ID 전달은 필요 없다. FE는 `credentials: "include"`로 호출한다. GET 요청에는 CSRF 헤더가 필요 없다.

1. 기존 SecurityConfig가 스톡스푼 Access JWT를 검증한다.
2. UserController가 JWT subject의 스톡스푼 사용자 ID를 UserService에 전달한다.
3. UserService가 UserRepository로 users를 조회한다.
4. UserMeResponse로 닉네임과 이미지 URL만 반환한다. 카카오 API를 다시 호출하지 않는다.

성공 응답은 `200 OK`이다.

```json
{"nickname":"스톡스푼 사용자","profile_image_url":"https://example.com/profile.jpg"}
```

이미지가 없으면 `profile_image_url`은 `null`이다. 인증되지 않았거나 토큰이 유효하지 않으면 `401 UNAUTHORIZED`, 유효한 토큰의 사용자가 DB에 없으면 `404 USER_NOT_FOUND`와 `사용자를 찾을 수 없습니다.` 메시지를 반환한다. 오류 body는 기존과 동일한 `code`, `message` 형식이다.

## 3. 코드별 역할과 처리 순서

아래 경로는 `src/main/java/com/stock_spoon/river_be/` 기준이다.

| 순서 | 파일과 메서드 | 역할 | 변경 여부 |
|---|---|---|---|
| 1 | `config/SecurityConfig.java` — `accessTokenResolver()`, `securityFilterChain()` | 쿠키의 Access JWT를 기존 디코더로 검증하고 인증된 요청만 통과 | 기존 코드 재사용 |
| 2 | `user/controller/UserController.java` — `getMe()` | GET 요청을 받고 검증된 JWT subject를 사용자 ID로 전달 | 추가 |
| 3 | `user/service/UserService.java` — `getMe()` | 읽기 전용 트랜잭션에서 사용자 조회 후 응답 생성 | 추가 |
| 4 | `user/repository/UserRepository.java` — `findById()` | JPA를 통해 `users.user_id`로 조회 | 기존 코드 재사용 |
| 5 | `user/dto/UserMeResponse.java` | `nickname`, `profile_image_url` 두 필드로 응답 | 추가 |
| 예외 | `user/exception/UserNotFoundException.java` | DB에 사용자가 없는 상황 표현 | 추가 |
| 예외 | `user/exception/UserExceptionHandler.java` — `handle()` | 사용자 없음 예외를 404 및 `code`, `message` JSON으로 변환 | 추가 |

전체 흐름은 **FE 요청 → 쿠키 JWT 검증 → 토큰의 사용자 ID 확인 → users DB 조회 → 닉네임·이미지 URL 반환**이다.

요청에 다른 사용자 ID를 붙여도 조회 대상은 바뀌지 않는다. 카카오 서버를 다시 호출하지 않으며, JWT에 담긴 프로필이 아니라 DB의 현재 값을 반환한다. 카카오에서 프로필을 바꾼 경우에는 기존 로그인 동기화가 수행된 후 변경 내용이 반영된다.

## 4. FE 호출 예시

`BE_ORIGIN`은 실제 BE 주소(로컬 예: `http://localhost:8080`)로 지정한다.

```javascript
const response = await fetch(`${BE_ORIGIN}/api/v1/users/me`, {
  method: "GET",
  credentials: "include"
});
const body = await response.json();
if (!response.ok) {
  // HTTP 상태와 body.code에 따라 오류 처리
  throw new Error(body.message);
}
// body.nickname, body.profile_image_url로 화면 표시
```

브라우저가 로그인 쿠키를 전송하므로 FE에서 HttpOnly 토큰을 직접 읽을 필요는 없다. FE 출처는 기존 BE CORS 설정에서 허용되어 있어야 한다.

## 5. 검증 결과 및 함께 변경한 설정

`src/test/java/com/stock_spoon/river_be/user/controller/UserControllerTests.java`에 다음 5가지 통합 테스트를 추가했다. 테스트용 H2 DB, 실제 JWT 발급 코드, Spring Security 필터와 MockMvc를 사용한다.

1. 토큰 발급 후 DB 프로필이 바뀌어도 최신 DB 값을 반환하며, 다른 사용자 ID를 요청에 넣어도 본인 정보만 조회한다.
2. 프로필 이미지가 없으면 JSON에 `profile_image_url: null`이 포함된다.
3. 인증 쿠키가 없으면 `401 UNAUTHORIZED`를 반환한다.
4. Refresh Token을 Access Token 대신 사용하면 401로 거절한다.
5. 토큰 발급 후 사용자가 DB에서 삭제되면 `404 USER_NOT_FOUND`를 반환한다.

구현 후 `gradlew.bat test`로 기존 테스트와 신규 테스트가 모두 통과했다. 이는 BE 자동 테스트 결과이며, 실제 FE 브라우저 및 카카오 서버와의 연결을 검증한 결과는 아니다.

이 브랜치에서 `.env.local`이 Git 제외 대상에 없었으므로 `.gitignore`에 추가했다. 내 정보 조회를 위해 엔티티나 DB 컬럼을 추가할 필요는 없었다.
