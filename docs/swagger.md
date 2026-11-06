# Swagger API 문서

## 실행

기존 방식으로 백엔드를 실행한 뒤 `/api/swagger-ui/index.html`에 접속한다.
기본 로컬 주소는 `http://localhost:8080/api/swagger-ui/index.html`이다.
JSON 명세는 `/api/v3/api-docs`, YAML 명세는 `/api/v3/api-docs.yaml`에서 확인한다.
DB·Redis 등 서버 실행에 필요한 설정은 기존과 동일하다.

`SWAGGER_ENABLED=false`이면 UI와 명세 엔드포인트를 모두 비활성화한다.
기본값은 true이므로 문서 공개를 원하지 않는 운영 배포에는 반드시 false를 지정한다.
Swagger 경로의 GET 요청만 공개하며 실제 API의 인증·인가·CSRF 규칙은 유지한다.

## 로그인과 요청 실행

1. 프론트에서 정상적으로 카카오 로그인을 진행한다. Swagger 서버 주소와 같은 호스트에서 발급한 쿠키가 필요하다.
2. 같은 브라우저에서 Swagger를 연다. 브라우저가 HttpOnly access_token 쿠키를 자동 전송한다.
3. 또는 프론트에서 state 검증을 마친 새 카카오 인가 코드를 Swagger의 로그인 API에 전달한다.
4. POST/PATCH/DELETE 실행 직전에 interceptor가 `/api/v1/auth/csrf`를 호출하고 응답 token을 X-XSRF-TOKEN 헤더로 전달한다.
5. CSRF 쿠키는 HttpOnly를 유지한다. 쿠키를 JavaScript에서 읽거나 보안을 끄지 않는다.

Authorize 입력창으로 브라우저의 Cookie 헤더를 설정할 수 없다. 실제 로그인을 사용한다.
쿠키의 Secure·SameSite·Domain·Path 설정이 접속 주소와 맞아야 한다.
localhost와 127.0.0.1 또는 서로 다른 서브도메인은 로그인 쿠키를 공유하지 않을 수 있다.
CSRF 토큰을 가져오지 못하면 쓰기 요청을 중단한다. 외부 서버 URL 실행은 차단한다.
AI 서버 전용 API는 해당 AI 권한 토큰이 필요하며 일반 사용자 로그인으로 실행할 수 없다.

## API 명세 시트와 구현 차이

참고: https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152

2026-11-05 기준 확인한 대표 차이이며 전체 명세의 일치 보증은 아니다.
Swagger의 경로·DTO·기본 상태 코드는 현재 구현에서 생성한다. 문서화를 위해 API 동작은 바꾸지 않는다.

- 시트의 프로필 수정·presigned URL 발급은 현재 UserController에 구현되어 있지 않아 문서에 새 API로 만들지 않았다.
- 온보딩은 시트의 `/api/v1/users/me/onboarding`과 달리 현재 POST `/api/v1/users/me`이다.
- 별도 `/api/v3/users/me/competitions/ai-settings`는 현재 없으며 AI 전략은 대결 생성 요청의 agents에 통합되어 있다.
- 주문 생성은 동기 201 외에 큐 사용 시 202를 반환할 수 있다. Object/Map 응답은 자동 생성 스키마가 상세 DTO보다 제한적이다.
- 카카오 토큰·사용자 정보 주소는 백엔드가 호출하는 외부 API로 이 서버의 API 목록에 추가하지 않았다.
- 인증·사용자·계좌·주문·종목·대결·AI 리포트·AI 서버 전용 태그로 구현된 API를 묶었다.
- 주요 입력과 종목 응답에 예시를 추가했다. 나머지 필드는 DTO·Jackson 이름·검증 제약에서 생성되며 필요한 상세 예시는 점진적으로 보완할 수 있다.

## 검증

```powershell
.\gradlew.bat test --tests '*OpenApi*Tests' --tests '*SecurityJwtTests' --tests '*AuthControllerTests' --tests '*StockControllerTests' --tests '*WatchlistControllerTests' --tests '*CompetitionUnifiedCreationControllerTests'
node --test src/test/js/swagger-csrf.test.cjs
```

실제 카카오 계정 로그인과 운영 데이터 변경은 자동 테스트에서 실행하지 않는다.

## 공개 서버 경로

`https://stock-spoon.com/api/swagger-ui.html` 또는 `/api/swagger-ui/index.html`로 접속한다.
UI 리소스와 JSON/YAML 명세를 모두 `/api` 아래에 등록하여 백엔드 라우팅 경로와 일치시킨다.
문서 경로의 GET만 공개하며 `/api/**` 전체를 허용하지 않는다.
`/api`는 문서 URL의 접두 경로이지 servlet context path가 아니므로 CSRF 주소는 `/api/v1/auth/csrf`다.
