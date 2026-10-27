# 회원 탈퇴 실행 흐름

`DELETE /api/v1/users/me`는 카카오 연결을 해제한 뒤 로그인한 회원과 모든 소유 계좌·주문·대결 데이터를 물리 삭제한다. 처리 완료 시 본문 없는 `204 No Content`와 인증 쿠키 만료 응답을 반환한다. 탈퇴 접수용 테이블이나 전용 스케줄러는 사용하지 않는다.

## 1. 호출 흐름

```text
DELETE /api/v1/users/me
  Cookie: access_token=StockSpoon Access JWT
  Cookie: XSRF-TOKEN=CSRF 쿠키
  X-XSRF-TOKEN: /api/v1/auth/csrf 응답의 token
  → config.SecurityConfig.securityFilterChain(...)에 설정된 CSRF 검증
  → config.JwtConfig.accessJwtDecoder(...)
    → 서명·만료·issuer·type 검증
    → user.repository.UserRepository.existsById(JWT subject)
  → config.SecurityConfig.securityFilterChain(...)의 권한 검사
  → user.controller.UserController.deleteMe(jwt, response)
    → JWT subject를 long userId로 변환
    → user.service.UserWithdrawalService.withdraw(userId)
      → TransactionTemplate.execute(...) [READ_COMMITTED]
        → UserRepository.findLockedById(userId)
        → CompetitionRepository.findAllLockedByUserId(userId)
        → AccountRepository.findAllLockedByUserId(userId)
        → UserOAuthRepository.findByUserId(userId)
          → 연결 정보가 있으면 auth.client.KakaoClient.unlink(providerUserId)
            → POST https://kapi.kakao.com/v1/user/unlink
        → user.repository.UserDeletionRepository.deleteAllForUser(userId)
          → EntityManager.flush()
          → 자식부터 native DELETE 실행
          → EntityManager.clear()
      → DB 커밋
      → OrderReservationStore가 구성되어 있으면 removeAll(accountId)
      → order.service.OrderSubscriptionService.refresh()
    → auth.cookie.AuthCookieService.clear(response)
  → 204 No Content (응답 본문 없음)
```

소스: [UserController](../src/main/java/com/stock_spoon/river_be/user/controller/UserController.java), [UserWithdrawalService](../src/main/java/com/stock_spoon/river_be/user/service/UserWithdrawalService.java), [UserDeletionRepository](../src/main/java/com/stock_spoon/river_be/user/repository/UserDeletionRepository.java), [KakaoClient](../src/main/java/com/stock_spoon/river_be/auth/client/KakaoClient.java), [JwtConfig](../src/main/java/com/stock_spoon/river_be/config/JwtConfig.java).

## 2. 순서대로 설명

탈퇴 요청이 들어오면 보안 필터에서 StockSpoon Access JWT와 CSRF를 검증한다. `JwtConfig`의 Access JWT decoder는 일반 회원의 토큰 subject를 회원 ID로 해석하고 `UserRepository.existsById()`로 회원 존재도 확인한다. 따라서 탈퇴 후 만료 시간이 남아 있는 이전 토큰도 인증에 사용할 수 없다. AI 서버 전용 토큰은 기존 전용 권한 검사를 유지한다.

`user.controller` 패키지의 `UserController.deleteMe()`는 인증된 `Jwt`를 `jwt` 인자로 받는다. 요청 본문이나 경로에서 회원 ID를 받지 않는다. 컨트롤러는 `jwt.getSubject()`를 `long`으로 바꾸어 `UserWithdrawalService.withdraw(userId)`에 전달한다.

`user.service`의 `UserWithdrawalService.withdraw()`는 `TransactionTemplate`으로 DB 트랜잭션을 시작한다. 먼저 회원 행을 잠그고, 그 회원의 대결과 계좌를 ID 오름차순으로 잠근다. 계좌 조회에는 활성 여부 조건이 없으므로 비활성 계좌도 삭제 대상이다. 기존 계좌·대결 생성 서비스가 사용하는 회원 잠금과, 체결·대결 정산이 사용하는 대결·계좌 잠금에 맞춰 동시에 데이터를 변경하지 못하게 한다.

이어서 `UserOAuthRepository.findByUserId(userId)`로 카카오 식별자를 읽는다. 연결 정보가 있으면 `auth.client.KakaoClient.unlink()`에 내부 userId가 아닌 `providerUserId`를 전달한다. 이 메서드는 서버의 `KAKAO_ADMIN_KEY`로 카카오 연결 해제 응답을 기다린다. 성공 응답의 ID가 요청 대상과 일치해야 한다. 이미 해당 앱에 연결되지 않은 사용자를 뜻하는 HTTP 400/code -101은 재요청에서도 진행할 수 있도록 허용한다. OAuth 연결 정보가 없는 내부 회원은 외부 호출 없이 삭제한다.

연결 해제가 확인되면 `UserDeletionRepository.deleteAllForUser(userId)`가 연관 데이터를 자식부터 삭제한다. bulk SQL은 JPA의 cascade를 실행하지 않으므로 리포트의 상세 컬렉션과 체결 등도 각각 삭제한다. 삭제 전 `flush()`로 기존 변경을 반영하고, 삭제 후 `clear()`로 영속성 컨텍스트에 남은 엔티티가 다시 저장되는 일을 방지한다. 하나라도 실패하면 DB 삭제 전체가 롤백된다.

DB 커밋 후에는 서비스가 보관한 계좌 ID 목록으로 Redis 임시 예약을 정리하고, `OrderSubscriptionService.refresh()`로 남은 주문에 필요한 종목 구독 수요를 다시 계산한다. 마지막으로 컨트롤러가 `AuthCookieService.clear(response)`를 호출해 Access/Refresh 쿠키를 만료시키고 204를 반환한다. 공용 종목 및 시세 데이터는 삭제하지 않는다.

## 3. 전달값

| 호출 | 값의 출처·입력 | 처리·반환 |
|---|---|---|
| `deleteMe(jwt, response)` | 보안 검증 후 주입된 JWT, HTTP 응답 객체 | `userId`를 서비스로 전달 |
| `withdraw(userId)` | JWT subject를 long으로 변환 | 잠금·연결 해제·DB 삭제·예약 정리 |
| `findAllLockedByUserId(userId)` | 인증된 회원 ID | 해당 회원의 전체 대결/계좌를 ID 순으로 잠금 조회 |
| `unlink(providerUserId)` | `user_oauth.provider_user_id` | 카카오 연결 해제 요청·응답 확인 |
| `deleteAllForUser(userId)` | 내부 회원 ID | 소유 데이터의 자식부터 삭제 |
| `removeAll(accountId)` | 삭제 전에 읽어둔 계좌 ID | 해당 계좌의 임시 예약과 Redis 계좌 인덱스 정리 |
| `clear(response)` | 실제 HTTP 응답 | 인증 쿠키 만료 헤더 |

## 4. 삭제 정책과 예시

| 삭제 순서 | 테이블·범위 |
|---|---|
| 1 | `ai_report_reasoning` → `ai_order_reports`, `executions` |
| 2 | `order_request_records` → `trade_orders`, `holdings` |
| 3 | `competition_daily_performances` → `competition_participants`, `competition_market_analyses`, `competition_judgment_summaries` → `competitions` |
| 4 | `watchlist_stocks`, `refresh_token` |
| 5 | `accounts` → `user_oauth` → `users` |

주문은 `PENDING`, `EXECUTED`, `CANCELLED` 모두 삭제한다. 계좌와 주문을 영구 삭제하므로 대기 주문에 취소 상태를 저장해 보존하는 단계는 추가하지 않는다. 대신 체결과 같은 계좌 잠금을 사용한다. 체결이 먼저 커밋되면 그 결과까지 삭제하고, 삭제가 먼저 끝나면 이후 `OrderExecutionService.executeLimit()`는 대상 부재를 확인해 `false`를 반환한다.

예를 들어 일반 계좌 하나에 지정가 대기 주문이 있고, 대결 계좌에 체결 내역과 AI 리포트가 있는 회원이 탈퇴하면 이 계좌와 이력은 모두 삭제된다. 다른 회원이 같은 종목을 관심 등록했더라도 그 회원의 등록과 공용 `stocks` 행은 유지된다.

같은 카카오 식별자로 다시 로그인할 때는 `KakaoAuthService.verify(code)`가 기존 `UserOAuth` 연결을 찾지 못하므로 새 `User`와 `UserOAuth`를 저장한다. 새 내부 회원 ID를 발급하고 `onboardingCompleted=false`부터 시작한다. 이전 이력은 복원하지 않는다. `AuthService.login()`의 동일 트랜잭션에서 기존 회원 로그인은 회원 잠금을 유지하며, `RefreshTokenService.create/rotate()`도 회원을 잠금 조회해 삭제된 회원에게 새 토큰을 저장하지 않는다.

## 5. 지연 주문·Redis 정리 흐름

```text
SQS 메시지 도착
  → order.messaging.OrderRequestListener.onMessage(message)
  → OrderRequestService.consume(request)
    → OrderRequestTransactions.discardIfAccountDeleted(accountId)
      → AccountRepository.findLockedById(accountId)
      → 실제 계좌 부재이면 OrderReservationStore.removeAll(accountId)
    → 부재이면 null 반환, 주문/요청 이력 생성 없음
  → 리스너 정상 종료, ON_SUCCESS ACK

기존 주문 정리 주기 (기본 30초)
  → OrderRequestListener.reconcile()
  → OrderRequestService.reconcile()
    → store.accounts()의 각 계좌 검사
    → discardIfAccountDeleted(accountId)
    → 없는 계좌의 예약 제거, 재발행 생략
```

[OrderRequestService](../src/main/java/com/stock_spoon/river_be/order/messaging/OrderRequestService.java)는 메시지를 받은 시점과 처리 중 오류 발생 시 계좌 부재를 확인한다. 계좌 삭제는 정상적인 종료 조건이며, DB 조회 장애를 부재로 간주하지 않는다. 정상 조회에 실패하면 예외를 전파해 SQS 재시도 대상으로 남긴다. 주문 처리 직후 탈퇴가 커밋되는 경우도 `cleanup()`이 부재를 처리하고, 초기 체결 알림은 주문이 남아 있을 때만 실행한다.

[RedisOrderReservationStore.removeAll()](../src/main/java/com/stock_spoon/river_be/order/reservation/RedisOrderReservationStore.java)은 `order:temporary:{accountId}` 해시와 `order:temporary:accounts` 집합의 해당 계좌 항목을 지운다. Redis 장애로 즉시 정리가 실패하면 로그를 남기고 기존 reconcile이 후속 정리를 수행한다. 큐 소비자가 실행되는 환경에서 Redis와 DB가 복구되고 다음 주기가 돌아야 후속 정리가 이루어진다.

대결 정산·일별 성과 작업도 이미 삭제된 대결이면 정상 반환한다. 새로운 탈퇴 전용 스케줄러는 없다.

## 6. 예외와 트랜잭션 경계

| 조건 | 발생 위치·결과 |
|---|---|
| 토큰 없음·위조·만료·삭제된 회원 | 보안 필터 → 401 `UNAUTHORIZED` |
| CSRF 없음·불일치 | 보안 필터 → 403 `INVALID_CSRF_TOKEN` |
| 인증 검사 직후 회원 삭제 | 회원 잠금 조회 실패 → 404 `USER_NOT_FOUND` |
| Admin 키 미설정 | `KakaoClient.unlink()` → 503 `KAKAO_UNLINK_NOT_CONFIGURED` |
| 카카오 연결/응답 타임아웃 | 504 `OAUTH_PROVIDER_TIMEOUT` |
| 카카오 응답 오류·대상 ID 불일치 | 502 `OAUTH_PROVIDER_ERROR` |
| DB 삭제 실패 | 예외 전파, DB 트랜잭션 전체 롤백. 인증 쿠키 만료 처리 전 종료 |
| 커밋 후 Redis 정리 실패 | 경고 로그, DB 삭제는 유지하고 기존 reconcile에서 정리 |

카카오 호출에는 기존 RestClient의 연결 3초·응답 5초 타임아웃을 사용한다. 동기 구현에서 같은 회원의 탈퇴를 직렬화하기 위해 카카오 호출 동안 DB 잠금을 유지한다. 별도 백그라운드 재시도는 하지 않는다.

카카오와 DB는 같은 트랜잭션이 아니다. 카카오 성공 후 DB가 실패하면 카카오 연결은 해제되고 DB 데이터는 남는다. 사용자는 기존 세션으로 탈퇴를 다시 요청할 수 있고, code -101인 경우 DB 정리를 이어간다. 타임아웃도 실제 연결 해제 여부가 불확실하므로 성공으로 응답하지 않는다. 이미 탈퇴한 회원의 재요청은 JWT 검사에서 거절되며 새로 가입한 회원을 삭제하지 않는다.

## 7. 설정과 검증

`KAKAO_ADMIN_KEY`는 카카오 REST API 키나 Client Secret과 다른 서버 전용 Admin 키다. 로그인에 사용하는 앱과 같은 앱의 키를 비밀 관리 도구로 주입한다. 미설정이어도 기존 로그인은 유지되지만 카카오 회원 탈퇴는 503으로 실패한다. [배포 환경변수 예시](redis/deployment-common.env.example)에 항목을 추가했다.

카카오 사양: [연결 해제 API](https://developers.kakao.com/docs/ko/kakaologin/rest-api#unlink), [이미 연결 해제된 사용자의 재요청 설명](https://devtalk.kakao.com/t/v1-user-unlink-api/125948).

`UserWithdrawalTests`는 H2/MySQL 모드와 실제 JPA·보안 필터를 사용하며 카카오만 대체한다. 연관 데이터 전체 삭제와 타 회원 보존, 쿠키 만료, 카카오 실패 시 데이터 보존, DB 중간 삭제 롤백, 재가입, 삭제된 대상의 지연 작업, 계좌 생성과 탈퇴 경쟁을 확인한다. `OrderRequestTransactionsTests`는 삭제 계좌 메시지의 반복 수신과 reconcile 정리를 검사한다. `KakaoClientTests`는 MockRestServiceServer로 Admin 헤더·폼·응답 검증과 오류 처리를 확인한다.

실제 카카오 연결 해제, 운영 MySQL 잠금 및 Redis/SQS 서비스에 대한 검증은 별도 실행하지 않았다. 실제 회원 데이터나 외부 연결을 변경하는 테스트는 수행하지 않는다.

최종 로컬 검증: `.\gradlew.bat test spotbugsMain --console=plain` 성공. 테스트 483개 중 465개 통과, 18개 조건부 건너뜀, 실패 0개. SpotBugs 통과. 별도 코드 리뷰에서 확인된 Critical/Important 지적은 없었다.

## 8. 패키지별 변경 목록과 코드 주석

구현의 추가·수정 지점에는 `[회원 탈퇴 추가]`, `[회원 탈퇴 수정]`, 테스트에는 `[회원 탈퇴 테스트 추가/수정]` 주석을 남겼다. 새 클래스는 클래스·생성자·메서드와 단계별 처리에 설명을 붙였다. 아래 목록은 해당 기능에서 바꾼 전체 코드·설정·테스트 파일이다.

| 패키지·파일 | 추가·수정한 로직과 이유 |
|---|---|
| [`user.controller.UserController`](../src/main/java/com/stock_spoon/river_be/user/controller/UserController.java) | 탈퇴 서비스와 쿠키 처리기를 주입하고 `deleteMe(jwt, response)`를 추가했다. JWT의 회원 ID만 사용하고 서비스 성공 후 쿠키를 만료해 204로 반환한다. |
| [`user.service.UserWithdrawalService`](../src/main/java/com/stock_spoon/river_be/user/service/UserWithdrawalService.java) | 새 서비스다. `withdraw(userId)`에서 회원 → 대결 → 계좌 잠금, 카카오 연결 해제, DB 삭제를 수행하고 커밋 후 예약·구독을 정리한다. |
| [`user.repository.UserDeletionRepository`](../src/main/java/com/stock_spoon/river_be/user/repository/UserDeletionRepository.java) | 새 Repository다. `deleteAllForUser(userId)`에서 삭제 범위를 회원 ID로 제한하고 상세·체결 등 자식부터 삭제한다. 마지막에 영속성 컨텍스트를 비운다. |
| [`user.repository.UserOAuthRepository`](../src/main/java/com/stock_spoon/river_be/user/repository/UserOAuthRepository.java) | `findByUserId(userId)`로 unlink에 필요한 카카오 회원번호를 조회한다. |
| [`account.repository.AccountRepository`](../src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java) | `findAllLockedByUserId(userId)`를 추가했다. 비활성 계좌를 포함한 모든 계좌를 ID 순으로 잠근다. |
| [`competition.repository.CompetitionRepository`](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionRepository.java) | `findAllLockedByUserId(userId)`를 추가해 기존 정산과 동일하게 대결을 계좌보다 먼저 잠근다. |
| [`auth.client.KakaoClient`](../src/main/java/com/stock_spoon/river_be/auth/client/KakaoClient.java) | `unlink(providerUserId)`를 추가했다. Admin 키 설정·폼·인증 헤더·성공 ID를 확인하고, 이미 연결 없는 -101만 재요청 가능한 경우로 허용한다. |
| [`auth.service.AuthService`](../src/main/java/com/stock_spoon/river_be/auth/service/AuthService.java) | `login()`을 READ_COMMITTED 트랜잭션으로 묶어 회원 확인부터 토큰 저장까지 잠금을 유지한다. |
| [`auth.service.KakaoAuthService`](../src/main/java/com/stock_spoon/river_be/auth/service/KakaoAuthService.java) | 기존 회원 로그인 시 `User`를 잠금 재조회한다. 예전에 읽은 OAuth 연결이 남아 있어도 이미 삭제된 회원을 로그인시키지 않는다. |
| [`auth.service.RefreshTokenService`](../src/main/java/com/stock_spoon/river_be/auth/service/RefreshTokenService.java) | `UserRepository`를 주입하고 `create()`·`rotate()`에서 회원을 먼저 잠금 조회해 탈퇴와 토큰 저장을 직렬화한다. |
| [`config.JwtConfig`](../src/main/java/com/stock_spoon/river_be/config/JwtConfig.java) | `accessJwtDecoder()`에 회원 존재 검사를 추가했다. 서명·만료 검증 후 삭제된 회원을 401로 거절하며 AI 서버 전용 토큰은 기존 경로를 유지한다. |
| [`config.KakaoProperties`](../src/main/java/com/stock_spoon/river_be/config/KakaoProperties.java) | `adminKey` 설정값을 추가했다. 로그인용 Client Secret과 다른 연결 해제 키다. |
| [`execution.service.OrderExecutionService`](../src/main/java/com/stock_spoon/river_be/execution/service/OrderExecutionService.java) | `executeLimit()`에서 계좌·주문이 이미 삭제되었다면 예외 대신 false를 반환해 늦은 시세 이벤트를 종료한다. |
| [`competition.service.CompetitionCompletionService`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionService.java) | `completeCompetition()`이 후보 선정 후 삭제된 대결이면 정상 반환한다. |
| [`competition.service.CompetitionDailyPerformanceService`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionDailyPerformanceService.java) | `recordMissingDays()`가 삭제된 대결에 성과를 추가하지 않고 종료한다. |
| [`order.messaging.OrderRequestService`](../src/main/java/com/stock_spoon/river_be/order/messaging/OrderRequestService.java) | `consume()`의 진입·오류 처리에서 삭제를 확인한다. 거절 저장 중 삭제도 재확인하고 초기 체결 알림은 주문이 남았을 때만 호출한다. `reconcile()`은 없는 계좌의 예약을 정리하고 재발행하지 않는다. |
| [`order.messaging.OrderRequestTransactions`](../src/main/java/com/stock_spoon/river_be/order/messaging/OrderRequestTransactions.java) | `discardIfAccountDeleted()`를 추가했다. 실제 부재일 때만 예약을 정리하며 DB 장애는 전파한다. `cleanup()`도 처리 후 삭제된 계좌를 허용한다. |
| [`order.reservation.OrderReservationStore`](../src/main/java/com/stock_spoon/river_be/order/reservation/OrderReservationStore.java) | 계좌 단위 `removeAll()` 계약을 추가하고 기본 구현은 단건 삭제를 재사용한다. |
| [`order.reservation.RedisOrderReservationStore`](../src/main/java/com/stock_spoon/river_be/order/reservation/RedisOrderReservationStore.java) | `removeAll()`에서 계좌 예약 해시와 계좌 목록의 항목을 함께 삭제한다. |
| [`application.yaml`](../src/main/resources/application.yaml) | `kakao.admin-key`를 `KAKAO_ADMIN_KEY` 환경변수에 연결했다. 미설정 시 기동을 유지하고 unlink에서 503을 반환한다. |
| [`deployment-common.env.example`](redis/deployment-common.env.example) | 배포 환경변수 예시에 서버 전용 Admin 키를 추가했다. 실제 키는 기록하지 않는다. |
| [`auth.client.KakaoClientTests`](../src/test/java/com/stock_spoon/river_be/auth/client/KakaoClientTests.java) | 테스트 설정에 Admin 키를 추가하고 unlink의 인증·폼·응답 ID·이미 연결 없음·잘못된 키·타임아웃·미설정을 확인한다. |
| [`auth.service.KakaoAuthServiceTests`](../src/test/java/com/stock_spoon/river_be/auth/service/KakaoAuthServiceTests.java) | 설정 생성자의 추가 인자와 기존 회원 잠금 재조회 응답을 보완해 기존 로그인 테스트를 유지한다. |
| [`config.SecurityJwtTests`](../src/test/java/com/stock_spoon/river_be/config/SecurityJwtTests.java) | 새 회원 존재 검사에 맞게 실제 회원 fixture를 추가하고, 일반 회원의 AI 전용 API 접근을 계속 403으로 거절하는지 확인한다. |
| [`order.messaging.OrderRequestTransactionsTests`](../src/test/java/com/stock_spoon/river_be/order/messaging/OrderRequestTransactionsTests.java) | 삭제 계좌의 반복 메시지가 이력을 재생성하지 않고, reconcile이 예약을 지우며 재발행하지 않는지 확인한다. |
| [`user.controller.UserControllerTests`](../src/test/java/com/stock_spoon/river_be/user/controller/UserControllerTests.java) | 삭제된 회원의 JWT가 서비스의 404 대신 인증 단계의 401이 되는 계약에 맞춰 검증을 수정했다. |
| [`user.controller.UserWithdrawalTests`](../src/test/java/com/stock_spoon/river_be/user/controller/UserWithdrawalTests.java) | 새 통합 테스트다. 전체 삭제·공용 데이터 보존·CSRF·외부 실패·DB 롤백·재가입·지연 작업·동시 계좌 생성을 검증한다. |

관련 설명 문서는 이 파일을 추가하고, [로그인·인증 흐름](stockspoon_login_auth_flow.md)의 탈퇴 정책·환경변수와 [계좌 흐름](stockspoon_account_flow.md)의 이력 삭제 정책을 갱신했다. [구현 계획](superpowers/plans/2026-10-27-user-withdrawal.md)에는 완료 상태와 운영 환경 검증의 한계를 기록했다.
