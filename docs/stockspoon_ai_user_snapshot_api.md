# AI 서버용 전체 사용자 조회 API 구현

작성일: 2026-10-01. 브랜치: `feat/31-ai-server-connect-api`.
DB 조회와 인증을 준비한 [1단계](stockspoon_ai_user_snapshot_phase1.md),
[2단계](stockspoon_ai_user_snapshot_phase2_auth.md)에 이어 실제 GET 응답을 연결했다.
최신 `dev`의 대기 주문 기반 WebSocket 구독과 지정가 체결을 재사용한다.

## 확정한 계약

- `GET /api/v1/users/ai-server`. 요청 body와 필수 query parameter는 없다.
- `access_token` 쿠키로 JWT를 보낸다. HS256 서명, BE와 동일한 `iss`, 유효한 만료,
  `type=access`, `sub=ai-server`, `actor=AI`가 필요하다. Bearer 헤더만으로는 인증되지 않는다.
- 모든 사용자를 반환한다. 활성 AI 관리 계좌가 없는 사용자도 `accounts: []`로 포함한다.
- 계좌는 `is_active=true`, `is_ai_managed=true`만 포함한다. 보유종목과 `PENDING` 주문을 반환한다.
- `cash_balance`는 DB의 현금 잔액이다. 예약금을 제외한 `available_cash`는 이번 계약에 없다.
- `state`, 닉네임, `is_duel_account`, `is_ai_managed`는 조회 응답에 추가하지 않는다.
- `current_stock_price`는 유효한 구독에서 **마지막으로 수신한 가격**이다.
  응답 직전에 새로 조회한 가격을 의미하지 않는다. 개별 가격의 수신 후 경과 시간은 제한하지 않는다.
- 대기 주문 중 한 종목이라도 사용할 가격이 없으면 일부 사용자·주문을 반환하지 않고 전체 요청을 503으로 처리한다.

계약 근거: 사용자가 확정한 API 응답과 대화에서 결정한 계좌 필터, 전체 요청 오류,
별도 가격 유효시간을 두지 않는 v1 정책. 응답 필드는
[API 설계 문서](https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152)의 전체 유저 정보 조회와 맞췄다.

## 요청에서 응답까지

1. `SecurityConfig`가 쿠키 JWT를 검증하고 조회 경로에서 AI 서버 클레임을 확인한다.
   이 서비스 토큰으로 다른 보호 API를 요청하면 403이다.
2. `AiUserSnapshotController`가 `AiUserSnapshotService.snapshot()`을 호출한다.
3. 서비스의 읽기 전용 DB 트랜잭션에서 활성 AI 관리 계좌를 읽고, 그 계좌 ID 목록으로
   보유종목과 대기 주문을 일괄 조회한다. 계좌마다 쿼리를 반복하지 않는다.
4. 대기 주문 종목별로 `KiwoomStockStream.latest(code)`를 읽는다. 같은 종목은 한 응답에서
   한 번 읽어 여러 계좌·주문에 같은 값을 사용한다. GET은 신규 구독이나 REST 가격 조회를 하지 않는다.
   구독 수명은 기존 `OrderSubscriptionService`가 대기 주문에 맞춰 관리한다.
5. `latest()`의 기존 세션·종목 등록 검사와 마지막 연결 메시지 90초 기준을 사용한다.
   이 90초는 개별 가격의 나이를 검사하는 규칙이 아니다. 연결 종료·구독 해제 시 캐시도 제거된다.
6. 필요한 가격이 없으면 `AiSnapshotUnavailableException`을 발생시키고
   `UserExceptionHandler`가 503 오류 본문을 반환한다. 빈 대기 주문 목록에는 가격 조회가 필요 없다.
7. 계좌 ID로 보유종목·주문을 묶고, 모든 사용자를 ID 순서로 조회해 계좌를 연결한다.
   `AiUserSnapshotResponse`가 snake_case 필드와 소문자 주문 구분값으로 직렬화된다.

DB의 여러 조회와 외부 시세 캐시는 서로 하나의 원자적 스냅샷이 아니다. 조회 도중 체결이나
연결 종료가 발생하면 시점 차이가 있을 수 있다. 이 GET의 결과가 주문 가능 현금·수량을 보장하지는 않으며,
실제 주문 생성·체결 서비스는 그때의 DB 상태와 계좌 잠금으로 다시 검사한다.

## 응답 예시

설명용 가상 값이다. 계좌·보유종목·대기 주문이 없으면 해당 목록은 빈 배열이다.
전체 사용자가 없으면 `{"users":[]}`를 반환한다.

```json
{
  "users": [
    {
      "user_id": 1,
      "accounts": [
        {
          "account_id": 11,
          "account_name": "AI 계좌",
          "is_active": true,
          "cash_balance": 1000000,
          "stocks": [
            {"stock_code": "005930", "total_cost": 1000000.00, "quantity": 10}
          ],
          "pending_orders": [
            {
              "order_id": 3,
              "stock_code": "005930",
              "order_side": "sell",
              "order_status": "pending",
              "order_type": "limit",
              "limit_price": 250000,
              "quantity": 2,
              "current_stock_price": 200000
            }
          ]
        }
      ]
    },
    {"user_id": 2, "accounts": []}
  ]
}
```

금액과 수량은 JSON 숫자로 반환한다. `cash_balance`·수량은 Java `long`,
`total_cost`·현재가는 `BigDecimal`이다. Java 내부의 camelCase 이름은 응답 DTO의
`@JsonProperty`로 문서의 필드명에 맞춘다. 성공 응답에는 `message`를 추가하지 않는다.

사용할 가격이 없는 경우:

```json
{
  "code": "MARKET_DATA_UNAVAILABLE",
  "message": "대기 주문 종목의 현재가를 확인할 수 없습니다."
}
```

인증이 없거나 JWT 검증에 실패하면 401, 인증은 됐지만 AI 조회 클레임이 맞지 않으면 403이다.
DB 조회 실패는 가격 누락 오류로 변환하지 않는다.

## dev 병합 시 반영한 차이

- 계좌 상세 조회 테스트 충돌은 최신 `AccountDetailResponse`의 11개 필드 계약에 맞춰 해결했다.
  AI 조회 계좌의 필터는 계속 `isAiManaged()`를 사용한다.
- 새 `AccountCreateResponse`의 이전 `isAiDelegated()` 호출을 `isAiManaged()`로 맞췄다.
  계좌 생성 응답의 `is_ai_managed` 필드명과 엔티티의 변경된 메서드를 일치시키기 위한 수정이다.

## 검증

```powershell
.\gradlew.bat test --tests '*AiUserSnapshot*Tests' --tests '*SecurityJwtTests' --tests '*AccountControllerTests' --tests '*UserControllerTests' --no-daemon
```

`test`는 지정한 자동 테스트 실행, `--tests`는 테스트 클래스 필터,
`--no-daemon`은 이번 실행의 Gradle 데몬을 계속 유지하지 않는 옵션이다.
결과는 36개 테스트 통과, `BUILD SUCCESSFUL`이었다. H2 DB와 MockMvc, 가짜 시세 저장소를 사용했다.

병합한 주문·체결·웹소켓 기능의 영향도 다음 명령으로 확인했다.

```powershell
.\gradlew.bat test --tests '*Order*Tests' --tests '*ExecutionTests' --tests '*HoldingTests' --tests '*KiwoomStockStreamTests' --no-daemon
```

추가 45개 테스트도 모두 통과했다. 두 실행에서 총 81개 테스트를 검증했다.

- 실제 JWT 서명 검증을 거친 정상 JSON과 401·403 접근 제한
- 여러 사용자·계좌, 비활성·AI 비관리 계좌 제외, 계좌 없는 사용자 유지
- 보유종목·대기 주문만 포함, 동일 종목 가격의 응답 내 공유
- 개별 가격 수신 시각이 오래돼도 사용, 가격 누락 시 전체 요청 503
- 사용자 없음과 주문 없는 계좌, 기존 사용자·계좌 API 회귀

라이브 키움·배포 MySQL을 통한 이번 GET의 종단간 검증은 수행하지 않았다.
추가 DB 컬럼이나 테이블은 이번 GET 구현에 필요하지 않다. 배포 DB에는 앞선
`is_ai_managed` 컬럼 변경과 `dev` 주문·체결 테이블 변경이 별도로 적용되어 있어야 한다.

## 관련 코드

- `user/controller/AiUserSnapshotController.java`: GET 진입점
- `user/service/AiUserSnapshotService.java`: DB 집계와 마지막 현재가 연결
- `user/dto/AiUserSnapshotResponse.java`: 최종 응답 필드
- `user/exception/AiSnapshotUnavailableException.java`, `UserExceptionHandler.java`: 503 응답
- `config/SecurityConfig.java`: AI 서버 쿠키 인증·인가
- `order/OrderSubscriptionService.java`: 대기 주문 기반 구독 관리
