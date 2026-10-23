# 사용자 주문 생성 지원 변경 내역

작성일: 2026-10-17. 구현 커밋: `d2226cb` (`feat: support user-created limit orders`).

기존 AI 전용 지정가 주문 생성 경로에 사용자 직접 주문을 추가했다. API 경로는 유지하고,
검증된 JWT의 `actor`로 주문 주체를 결정한다. USER는 본인의 수동 운용 계좌에만,
AI는 본인의 AI 운용 계좌에만 주문할 수 있다.

## 1. OrderController.create(): 주문 주체 구분과 AI 전용 검증 분기

파일: [OrderController.java](../src/main/java/com/stock_spoon/river_be/order/controller/OrderController.java)

대상 API: `POST /api/v1/accounts/{accountId}/orders`

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| actor 검사 | `AI`가 아니면 `403 AI_ORDER_ONLY` | `AI`, `USER`만 허용. 누락·다른 값은 `403 INVALID_ORDER_ACTOR` |
| 주문 출처 결정 | 컨트롤러에서 구분하지 않음 | `AI` → `Order.Source.AI`, `USER` → `Order.Source.USER` |
| 보고서 입력 검증 | 모든 요청에 `validateReportInputs(side)` 호출 | AI 주문에만 호출 |
| 주문 근거 필수 검사 | 모든 요청의 `reason` 필수 | AI 주문만 null·공백 거부 |
| 계좌 사전 검사 | `assertOrderableAccount(userId, accountId)` | `assertOrderableAccount(userId, accountId, source)` |
| 주문 저장 호출 | `reserveLimit(userId, accountId, request)` | `reserveLimit(userId, accountId, request, source)` |

`source`는 요청 본문에서 받지 않고 인증된 토큰에서 결정한다. `sub`를 사용자 ID로
해석하는 처리, 지정가·매수/매도 구분 검사, 시장가 거부, 종목·호가 단위 검증은 유지했다.
JWT 서명·발급자·유효기간·토큰 종류 검증은 기존 Security 설정에서 수행한다.

`validateReportInputs(side)`의 내부 구현은 변경하지 않았다. 이 메서드는
`holding_weight_limit_percent`가 있으면 유한한 0~100인지 검사하고, AI 매수에서는
해당 값을, AI 매도에서는 `is_lower_triggered`를 필수로 요구한다. `reason` 검사는 별도다.

## 2. JwtTokenProvider.encode(): 사용자 토큰에 actor 추가

파일: [JwtTokenProvider.java](../src/main/java/com/stock_spoon/river_be/auth/token/JwtTokenProvider.java)

기존 `iss`, `sub`, `iat`, `exp`, `jti`, `type`에 `.claim("actor", "USER")`를 추가했다.
로그인과 재발급이 공통 발급 메서드를 사용하므로 두 경로 모두 적용된다.
이 메서드는 액세스·리프레시 토큰을 함께 발급하므로 두 토큰에 USER 클레임이 포함된다.
주문 API는 기존대로 `type=access` 토큰만 인증한다.

기존 액세스 토큰에는 `actor`가 없어 주문 생성이 거부된다. 재로그인하거나 기존
리프레시 토큰으로 재발급받으면 새 액세스 토큰에 USER 클레임이 포함된다.

## 3. OrderCreateRequest: reason의 null 허용

파일: [OrderCreateRequest.java](../src/main/java/com/stock_spoon/river_be/order/dto/OrderCreateRequest.java)

`reason`의 `@NotBlank`를 제거하고 `@Size(max = 100000)`는 유지했다.
사용자 주문에서 `reason`을 생략하거나 null로 보낼 수 있다. AI 주문의 근거 필수 조건은
컨트롤러의 AI 분기에서 검사하고, 서비스의 기존 공백·길이 검사도 유지한다.

`reasoning`, `holding_weight_limit_percent`, `is_lower_triggered` 등 기존 nullable
필드는 그대로 둔다. 사용자 주문에서는 `validateReportInputs()`를 호출하지 않는다.
다만 필드를 실제로 보내면 DTO의 길이·형식·타입 등 공통 제약은 계속 적용된다.

## 4. OrderService: 계좌 유형 검증과 주문 출처 저장

파일: [OrderService.java](../src/main/java/com/stock_spoon/river_be/order/service/OrderService.java)

### assertOrderableAccount() / assertAccountSource()

`source`를 받는 `assertOrderableAccount()` 오버로드를 추가했다.
기존 소유권·활성·대결 상태 검사를 유지하면서 새 `assertAccountSource()`로 다음을 확인한다.

| 주문 주체 | 허용 계좌 | 거부 계좌 |
|---|---|---|
| AI | `is_ai_managed=true` | 수동 운용 계좌 |
| USER | `is_ai_managed=false` | AI 운용 계좌 |

주체와 계좌 유형이 다르거나 source가 null이면 기본적으로 `403 FORBIDDEN_ACCOUNT`로 거부한다. 아래 v1 계좌 사용자 요청만 전용 오류를 반환한다.
source를 받지 않는 기존 메서드는 AI를 기본값으로 유지한다.

### 2026-10-23: v1 계좌 사용자 주문 안내

`account.entity.Account.getCreatedAt()`을 추가하고 `order.service.OrderAccountSourceValidator.assertAllowed(account, source)`가 계좌 유형 검증을 공통으로 수행하도록 수정했다. `OrderService.assertAccountSource()`와 `execution.service.OrderExecutionService`의 시장가 잠금 후 검사가 이를 호출한다. 별도 예외 클래스 대신 기존 OrderException의 코드·메시지를 사용한다.

```text
POST /api/v1/accounts/{accountId}/orders
 → order.controller.OrderController.create(jwt, accountId, request, eventId)
 → JWT actor를 Order.Source로 변환
 → OrderService.assertOrderableAccount(userId, accountId, source)
   → 소유자·활성 계좌 조회
   → assertAccountSource(account, source)
     → OrderAccountSourceValidator.assertAllowed(account, source)
       → 유형 일치: 통과
       → USER + AI 계좌 + createdAt < 2026-10-20T13:00:00Z: 전용 403
       → 그 외 불일치: 기존 FORBIDDEN_ACCOUNT
 → 정상 계좌만 기존 주문 처리 진행
```

Controller는 인증된 JWT subject의 사용자 ID, URL 계좌 ID, actor에서 결정한 source를 OrderService에 전달한다. Service는 계좌 소유권·활성을 먼저 확인한 뒤 공통 검증에 계좌와 source를 전달한다. 기준은 **2026-10-20 22:00 KST 이전**이며 Instant 비교값은 같은 시각인 `2026-10-20T13:00:00Z`다. 정확히 기준 시각에 생성된 계좌는 v1 안내 대상에서 제외한다.

유형 불일치 중 USER 요청·AI 계좌·기준 이전 생성 조건을 모두 만족하면 다음을 반환한다. AI 요청의 불일치, 기준 이후 AI 계좌, 생성 시각을 확인할 수 없는 계좌는 기존 오류를 유지한다. 이전에 만들어진 수동 계좌라도 유형이 일치하면 기존대로 통과한다. DB 데이터 변경·자동 계좌 전환·주문 허용 확대는 하지 않는다.

```json
{
  "code": "LEGACY_ACCOUNT_USER_ORDER_NOT_ALLOWED",
  "message": "이전 버전에서 생성된 계좌는 사용자 직접 주문을 지원하지 않습니다. 새 계좌를 생성해 주세요."
}
```

잠금 후 지정가·시장가 검사와 `order.messaging.OrderRequestTransactions.admit()/process()`가 호출하는 assertOrderableAccount에도 동일 규칙이 적용된다. FE는 이 오류 코드를 기준으로 안내할 수 있다. 기존 DB created_at이 실제 생성 시점을 올바르게 기록했다는 전제이며 운영 DB 시간대·저장값을 이번 코드 변경으로 보정하지 않는다.

검증: OrderAccountSourceValidatorTests는 기준 직전·정각·직후, AI 불일치, 정상 유형, 생성 시각 누락·source null을 검사한다. OrderControllerTests는 v1 AI 계좌의 USER 지정가·시장가 요청이 주문·체결·시세 구독 없이 전용 403을 반환하는지 확인한다.

### reserveLimit()

`reserveLimit(userId, accountId, request, source)` 오버로드를 추가했다.
사용자 주문은 입력된 `reason`을 null로 바꿔 내부 저장 메서드에 전달한다.
따라서 사용자가 근거를 보내더라도 AI 보고서는 생성하지 않는다.

계좌 잠금 후에도 `assertAccountSource()`를 다시 호출한다. 기존 소유권·대결 상태·
주문금액 오버플로·가용 현금·매도 가능 수량 검증은 유지한다.
저장 시 고정된 `Order.Source.AI` 대신 전달받은 source를 사용하므로
`trade_orders.order_source`에 `AI` 또는 `USER`가 기록된다.
기존 source 없는 예약 경로는 계속 AI 주문으로 처리한다.

### lockedAccount() / cancel()

공통 `lockedAccount()`에서 AI 운용 계좌만 허용하던 조건을 제거하고 활성 상태와
대결 상태 검사는 유지했다. 예약 경로의 계좌 유형 검사는 위의 `assertAccountSource()`로 옮겼다.
이로써 기존 `cancel()`도 수동 운용 계좌에서 동작한다. 취소 컨트롤러는 변경하지 않았다.
사용자 ID를 받는 취소 경로의 소유권 검사와 PENDING 상태 검사는 유지한다.

## 5. OrderHistoryService.list(): 사용자 체결 내역 조회

파일: [OrderHistoryService.java](../src/main/java/com/stock_spoon/river_be/order/service/OrderHistoryService.java)

기존 조회는 출처를 `Order.Source.AI`로 고정해 사용자 주문을 반환하지 않았다.
조회 대상 계좌가 AI 운용이면 AI, 수동 운용이면 USER를 검색하도록 변경했다.
목록 조회와 페이지 전체 개수 계산에 같은 출처를 적용했다.

보고서가 없는 경우 `reason.summary`를 null로 만드는 기존 처리를 재사용한다.
체결 완료 주문만 조회하는 정책과 대결 AI 내역 공개 제한은 유지한다.
GET 요청을 받는 `OrderController.list()` 자체는 변경하지 않았다.

## 6. 기존 동작을 재사용한 부분

- `Order.pendingLimit()`은 원래 reason이 null이면 보고서를 만들지 않는다.
- `OrderCreateResponse.from()`은 보고서가 없으면 stock_name을 null로 반환한다.
- 지정가 체결은 기존 `OrderExecutionService.executeLimit()`를 사용한다.
  보고서용 매수 비중 기록은 보고서가 없으면 기존 코드에서 건너뛴다.
- AI 리포트 조회는 기존 정책대로 사용자 주문에 대해 404를 반환한다.
- DB 컬럼 추가나 스키마 변경은 없다. 기존 `order_source`의 `AI`, `USER` 값을 사용한다.

## 7. 테스트 및 검증

변경 테스트:

- [AuthTokenFlowTests.java](../src/test/java/com/stock_spoon/river_be/auth/service/AuthTokenFlowTests.java): 로그인·재발급 액세스 토큰의 USER 클레임 확인.
- [OrderControllerTests.java](../src/test/java/com/stock_spoon/river_be/order/service/OrderControllerTests.java):
  사용자 매수·매도 접수, 근거 null과 보고서 미생성, 출처 USER 저장, 수동 계좌 주문 취소와
  예약 해제, actor 누락·미지원 값 거부, 주체와 계좌 유형 불일치 거부,
  보고서 없는 주문의 체결·자산 변경·일반 및 페이지 내역 조회 확인.

구현 검증 결과: 전체 339개 중 318개 통과, 외부 환경 테스트 21개 건너뜀, 실패 0개.
`gradlew.bat build --build-cache --console=plain` 성공. SpotBugs 포함.
실제 배포 MySQL·시장 데이터·외부 구독 환경 검증과 원격 푸시는 수행하지 않았다.
