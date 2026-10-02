# 주문 취소 API

## 요청과 응답

```http
PATCH /api/v1/accounts/{accountId}/orders/{orderId}
Content-Type: application/json

{"status":"cancelled"}
```

기존 JWT 인증을 사용한다. 쿠키 인증과 CSRF가 활성화된 환경에서는 기존 CSRF 정책도 적용된다.
status는 소문자 cancelled만 허용한다. 누락/null/다른 상태는 400이다.
성공 응답은 204 No Content이며 본문이 없다.
계좌 소유자가 아닌 경우 403, 해당 계좌에 주문이 없으면 404,
PENDING이 아닌 주문은 409이다. 이미 취소된 주문도 409이며 기존 취소 시각을 유지한다.
계좌 자체가 없거나 비활성 계좌 또는 자동매매 계좌가 아닌 경우 기존 서비스 정책에 따라 400이다.

## 파일과 실행 흐름

1. 기존 SecurityConfig가 JWT를 인증한다. 인증 없는 요청은 401이다.
2. 새 OrderCancelRequest.java가 요청 JSON을 받는다.
   @NotNull과 @Pattern으로 status가 cancelled인지 검증한다.
3. 기존 OrderController.java의 새 @PatchMapping("/{orderId}") 메서드가
   경로의 accountId/orderId와 인증 JWT의 subject에서 userId를 읽는다.
   orders.cancel(userId, accountId, orderId)를 호출하고 정상 종료하면 204를 응답한다.
4. 기존 OrderService.java의 새 cancel(userId, accountId, orderId)가 트랜잭션을 연다.
   기존 lockedAccount(accountId)로 계좌를 잠그고 운용 가능 여부와 소유권을 확인한다.
   기존 cancel(accountId, orderId)와 새 HTTP용 메서드는 private cancelPending을 공유한다.
5. cancelPending이 기존 OrderRepository.findByIdAndAccountId로
   해당 계좌에 속한 주문을 조회한다. 주문 존재와 PENDING 상태를 확인한 뒤
   기존 Order.cancel(clock.instant())를 호출한다.
6. 기존 Order.java의 cancel은 status=CANCELLED, reservedCash=0,
   cancelledAt=취소 시각으로 변경한다. quantity와 주문 기록은 유지한다.
7. 트랜잭션 커밋 시 JPA 변경 감지가 trade_orders를 UPDATE한다.
   delete나 별도 save 호출은 필요 없다.

## 예약 해제와 체결 경쟁

매수 가능 현금은 계좌 현금에서 PENDING 주문의 reservedCash 합계를 뺀다.
취소 시 현금 잔액 자체를 증가시키지 않는다.
매도 가능 수량은 보유 수량에서 PENDING SELL 주문의 수량 합계를 뺀다.
취소 후 CANCELLED 주문은 합산 대상에서 빠지므로 예약이 해제된다.
보유 수량 자체를 증가시키지 않는다.

기존 지정가 체결도 계좌를 잠근 후 주문 상태를 확인한다.
취소와 체결이 같은 계좌 잠금을 사용하므로 먼저 확정된 상태에 따라 뒤 요청을 처리한다.
Repository/Order 엔티티/DB 스키마는 추가 또는 변경하지 않는다.

## 검증

기존 OrderControllerTests.java에 다음 PATCH 테스트를 추가했다:
매수 예약 현금 해제와 DB 기록 보존, 매도 예약 수량 해제와 보유 수량 유지,
다른 소유자와 계좌-주문 불일치 거절, 누락/미지원 status 거절,
체결/취소 상태 거절, 인증 없는 요청 거절.
성공 사례는 flush/clear 이후 DB에서 다시 읽어 변경을 확인한다.

현재 주문 내역 API는 EXECUTED 주문만 조회한다.
취소 기록은 DB에 보존되지만 현재 주문 내역 API 목록에는 포함되지 않는다.

