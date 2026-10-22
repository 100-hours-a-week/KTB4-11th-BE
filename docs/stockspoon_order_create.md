# 주문 생성 API: 지정가 예약과 장중 시장가

2026-10-22 `codex/restore-market-orders` 구현 기준. 배포 완료를 뜻하지 않는다.
`POST /api/v1/accounts/{accountId}/orders`는 BE 내부 모의 주문을 만든다. 실제 증권사 주문 API는 호출하지 않는다.

## 인증 및 주문 주체

`access_token` 쿠키의 JWT와 CSRF 보호가 활성화된 환경의 CSRF 확인값을 사용한다.
공통 Security 설정이 서명·발급자·유효기간·`type=access`를 검증한다. 컨트롤러는 검증된 JWT의
`actor`가 `AI` 또는 `USER`인지 확인하고 주문 출처를 결정한다. 출처는 본문에서 받지 않는다.
JWT `sub`는 사용자 ID이며, URL의 accountId는 그 사용자가 소유한 활성 계좌여야 한다.
현재 계좌 유형 정책은 AI 주문은 AI 운용 계좌, USER 주문은 수동 계좌만 허용한다.
대결에 연결된 계좌는 현재 거래 가능한 대결 상태여야 한다.

사용자 로그인·재발급은 `actor=USER`를 발급한다. actor 없는 기존 액세스 토큰은 재발급 또는 재로그인이 필요하다.
AI 호출도 사용자 ID sub를 사용해야 하며, 목록 조회 전용 `sub=ai-server` 토큰으로 주문할 수 없다.

## 요청

USER 시장가 매수 예시:

```json
{
  "stock_code": "005930",
  "order_side": "buy",
  "order_type": "market",
  "limit_price": null,
  "quantity": 1
}
```

USER 지정가 매수 예시:

```json
{
  "stock_code": "005930",
  "order_side": "buy",
  "order_type": "limit",
  "limit_price": 70000,
  "quantity": 2
}
```

- stock_code는 숫자 6자리, quantity는 1 이상의 정수다. order_side는 buy/sell, order_type은 limit/market을 사용한다.
- 지정가 limit_price는 양수이며 일반 주식 호가 단위에 맞아야 한다. 시장가 limit_price는 생략하거나 null이어야 한다. 0도 허용하지 않는다.
- AI는 reason이 공백이 아닌 최대 100,000 Java UTF-16 코드 단위의 문자열이어야 한다. AI 매수는 holding_weight_limit_percent(유한한 0~100), AI 매도는 is_lower_triggered(JSON boolean)가 필수다.
- AI stock_name은 선택이며, 보내면 공백 불가·최대 255자다. reasoning은 선택 배열이며 label은 공백 불가·최대 255자, body는 공백 불가·최대 16,000자다. null 배열 원소는 거부한다.
- USER는 보고서 필드를 생략/null로 보낼 수 있고 AI 보고서를 만들지 않는다. 값을 실제로 보내면 DTO의 타입·길이 등 공통 제약은 적용되며 reason을 보내더라도 근거를 저장하지 않는다.
- holding_weight_limit_percent는 보고서 표시값이며 BE가 실제 비중 상한을 강제하는 조건이 아니다.

## 접수·체결 정책

| 유형 | 접수 시간 | 성공 결과 | 시세 연결 실패 |
|---|---|---|---|
| 지정가 | 장전·장후·야간·주말 포함 상시 | pending 또는 즉시 executed | 저장된 예약 유지 |
| 시장가 | 한국 시간 평일 09:00 이상, 15:30 미만 | 전량 executed | 신규 주문 저장 없이 실패 |

공휴일·임시 휴장 달력과 실제 거래소 장 상태는 연동하지 않는다. 시장가는 유효한 최신 호가가 있어야 체결한다.
지정가 접수의 OrderMarketValidator는 현재 가격의 호가 단위만 검사한다. 과거 ka10100 종목정보 조회·시장코드 검사·ETF 전용 단위 적용은 현재 이 경로에서 수행하지 않는다.

## 호출 흐름

```text
POST /api/v1/accounts/{accountId}/orders
 → Security 인증·CSRF → JSON 변환·@Valid
 → order.controller.OrderController.create(jwt, accountId, request)
   → actor → source, sub → userId, 주문 입력 검사
   → order.service.OrderService.assertOrderableAccount(userId, accountId, source)
   ├─ limit
   │  → order.validator.OrderMarketValidator.validateLimit(limitPrice)
   │  → OrderSubscriptionService.create(stockCode, 저장 작업)
   │     → OrderService.reserveLimit(userId, accountId, request, source)
   │        → 계좌 잠금·재검증 → OrderRepository.save() → 커밋
   │     → refresh(): 구독 수요 갱신
   │  → execution.listener.OrderExecutionListener.orderCreated(order)
   │     → 유효한 당일 장중 시세가 있으면 최초 지정가 체결 판단
   └─ market
      → OrderController.createMarketOrder(userId, accountId, request, source, side)
         → OrderExecutionService.isTradingTime()
         → AI 매수만 OrderBuyPriceService.fetch(accountId, stockCode)
         → OrderSubscriptionService.withMarketQuote(stockCode, 체결 작업)
            → 임시 구독 수요 추가 → 최신 QuoteEvent 확보
            → OrderExecutionService.executeMarket(userId, accountId, request,
                 source, quote.orderBook(), prices, validate)
               → 계좌 잠금·검증·호가별 체결 계산
               → 저장 직전 시간·호가·출처·대결 상태 재검사
               → 주문·체결·자산 변경 → EXECUTED 커밋
            → finally: 자기 임시 수요 해제
 → OrderExecutionListener.response(accountId, orderId)
   → OrderCreateResponse.from(order, executions) → HTTP 201
```

OrderController는 JWT, URL 계좌 ID, 요청 DTO를 각각 jwt/accountId/request로 받는다.
계좌 사전 검사는 소유권·활성·운용 유형·대결 상태를 확인한다. 실제 예약·체결 서비스는 계좌 잠금 후 이를 다시 검사한다.
지정가는 별도 트랜잭션에서 예약을 커밋한 뒤 시세 구독과 최초 체결 판단을 수행한다. 장외 또는 시세가 없으면 새 이벤트까지 기다린다.
시장가는 아직 DB 주문이 없어도 종목을 구독할 수 있도록 임시 수요를 추가한다. 최신 호가를 최대 3초 동안 50ms 간격으로 확인한다.
이 대기 시간은 계좌 잠금·DB 커밋을 포함한 전체 요청 제한 시간이 아니다. 호가 대기 중에는 DB 계좌 잠금을 잡지 않는다.
이미 구독 캐시에 호가가 있으면 즉시 체결 서비스로 전달한다. 이 호가가 만료되었으면 새 호가를 기다리지 않고 503으로 거부한다.

withMarketQuote는 호가의 종목·유형·출처를 확인하고 체결 작업에 QuoteEvent를 넘긴다.
executeMarket은 전달받은 book으로 가격·잔량을 계산하고 validate로 저장 직전 구독 출처를 재검증한다.
거래 시간과 호가 신선도는 계좌 잠금 후 및 자산 변경 직전에 검사한다. 호가 수신은 현재 이하·5초 이내,
호가 시각은 현재보다 과거 5초/미래 1초 범위이며 당일이어야 한다. exchangeTimestamp가 있으면 호가 시각과 일치해야 한다.
AI 매수의 다른 보유 종목 평가 가격은 기존 Redis 조회를 사용하며, 시장가 체결 가격을 대신하지 않는다.

구독 대상은 DB PENDING 종목과 처리 중인 시장가 요청 종목의 합집합이다. 같은 종목의 요청 수를 세므로
한 요청이 끝나도 다른 시장가 요청이나 지정가 예약의 구독을 제거하지 않는다. 정리 오류는 이미 성공한 거래 결과를 덮어쓰지 않는다.

## 자산 계산과 트랜잭션

- 가용 현금 = 현금 잔액 − 기존 PENDING 주문 예약금.
- 매도 가능 수량 = 보유 수량 − 기존 PENDING 매도 수량.
- 시장가 매수는 매도호가를 낮은 가격부터, 매도는 매수호가를 높은 가격부터 소비한다.
- 예: 70,000원 1주와 70,100원 2주를 매수하면 3주, 210,200원, 체결 2행이다.
- 시장가는 전량 체결이 가능한 경우만 저장한다. 잔량 부족 시 부분 주문·체결을 남기지 않는다.
- 주문·체결·현금·보유량을 같은 트랜잭션으로 처리한다. 후속 체결 저장 실패도 전체 롤백한다.
- 지정가 예약은 실제 현금·보유량을 접수 시 차감하지 않으며 체결·취소 시 예약을 해제한다.
- 동일 요청 재전송은 별도 주문으로 처리한다. 여러 요청 사이 호가 잔량을 공유 차감하는 기능은 없다.

## 응답·오류

성공은 HTTP 201이며 기존 OrderCreateResponse 구조를 사용한다. 시장가는 order_type=market,
limit_price=null, order_status=executed, reserved_cash=0과 executions를 반환한다.
지정가는 pending이면 executions가 비어 있다. created_at은 UTC Z 형식이다.
사용자 주문의 보고서 기반 stock_name은 null이며, 저장된 출처는 trade_orders.order_source의 AI/USER다.

| HTTP / code | 조건 |
|---|---|
| 401 UNAUTHORIZED / INVALID_TOKEN | 인증 실패 또는 사용자 ID 해석 실패 |
| 403 INVALID_CSRF_TOKEN | 활성화된 CSRF 검사 실패 |
| 403 INVALID_ORDER_ACTOR | actor 누락 또는 AI/USER 이외 값 |
| 403 FORBIDDEN_ACCOUNT | 계좌 소유권·활성 또는 운용 유형 검사 실패 |
| 409 COMPETITION_NOT_IN_PROGRESS | 대결 거래 제한 |
| 409 ORDER_WINDOW_CLOSED | 시장가 장외·주말 또는 잠금/처리 중 마감 도달 |
| 503 MARKET_STREAM_UNAVAILABLE | 구독·출처·호가 도착 대기 실패 |
| 503 MARKET_DATA_UNAVAILABLE | 체결용 호가 시간·가격 데이터 오류 |
| 400 INSUFFICIENT_CASH | 시장가 매수 가용 현금 부족 |
| 400 INSUFFICIENT_SELLABLE_QUANTITY | 시장가 매도 가능 수량 부족 |
| 400 INSUFFICIENT_MARKET_LIQUIDITY | 시장가 상대 호가 잔량 부족 |
| 400 INVALID_ORDER | 기타 주문 입력·지정가 검증 실패 |

시장가 단계별 로그와 CloudWatch 검색은 [로그 문서](cloudwatch-logging.md#시장가-주문-실패-추적)를 참고한다.
클래스별 복구 계획·실행 기록은 [시장가 복구 계획](superpowers/plans/2026-10-22-restore-market-orders.md)에 있다.
과거 AI 리포트 컬럼 이관은 [AI 리포트 문서](stockspoon_ai_report.md)를 참고한다. 이번 복구의 새 스키마 변경은 없다.
