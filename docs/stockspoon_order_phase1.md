> 2026-10-01 변경: 아래 decision_id/summary 계약과 주문 행의 근거 저장 설명은 이전 구현 기록입니다. 현재 reason은 문자열이며 주문과 1:1인 ai_order_reports에 저장합니다. 최신 계약은 [AI 리포트 상세 조회](stockspoon_ai_report.md)를 참고하세요.

# 주문 1단계: 지정가 예약과 취소 내부 규칙

예약·취소의 기본 규칙은 `03_주문_체결_정책_v1_확정본.md`를 따른다. 주문 생성 시간과 개별 종목 거래정지 검사는 이후 합의한 [현행 주문 생성 정책](stockspoon_order_create_flow.md)을 따른다. 기존 실행 모델의 `accounts` 단일 계좌에 주문과 보유종목을 연결한다. 별도의 ERD 초안에 있던 `investment_accounts`/`trading_portfolios`는 현재 실행 모델과 다르므로 그대로 적용하지 않았다.

## 현재 구현

- `trade_orders`: 계좌, 종목코드, 매수/매도, 지정가, 수량, 상태, 현재 예약금, 주문 주체, AI 판단 식별자/한 줄 요약, 시각을 저장한다. v1 내부 생성은 `AI`다.
- `holdings`: 계좌·종목별 보유수량과 총 취득원가를 저장한다. 주문 접수/취소에서는 보유수량과 현금 잔액을 변경하지 않는다.
- 지정가 매수: `available_cash = cash_balance - pending 주문의 reserved_cash 합계`. 주문 예약금은 지정가 × 수량이다.
- 지정가 매도: `sellable_quantity = 보유수량 - pending 매도 주문수량 합계`.
- 취소: `pending`만 `cancelled`로 전환하며 현재 예약금을 0으로 만든다. 매도 주문은 pending 합계에서 제외돼 수량 예약이 풀린다.
- 같은 계좌에 대한 예약/취소는 계좌 행에 `PESSIMISTIC_WRITE` 잠금을 잡고 한 DB 트랜잭션에서 검사·저장한다. 후속 체결도 동일한 잠금 순서를 따라야 한다.
- 실패한 접수는 주문 행을 남기지 않는다. 시장가 주문은 체결을 즉시 처리해야 하므로 이 단계에서는 생성하지 않는다.

## 후속 단계와 아직 연결하지 않은 항목

지정가 주문 생성 HTTP API와 주문 생성 시간·종목정보·호가단위 검증은 후속 단계에서 추가했다. 요청·응답과 인증은 [주문 생성 API 문서](stockspoon_order_create.md)를 참고한다. 체결 단계에서는 주문·체결·현금·보유종목을 한 트랜잭션에 묶어야 한다. `cancel`은 아직 내부 규칙만 있으며 수동 취소 HTTP 경로는 없다.

`reserved_cash`는 현재 예약된 금액이므로 pending 지정가 매수만 양수다. 취소 후 0이며, 체결 단계에서도 0으로 변경한다. `decision_id`/`decision_summary`는 현재 nullable이고 AI 연동에서 공급한다.

2026-09-28 로컬 MySQL 8.4.11의 `stockspoon` DB에 `db/order_phase1.sql`을 적용해 두 테이블을 생성했다. 기존 네 테이블의 데이터를 변경하지 않았다. 기본 `JPA_DDL_AUTO=validate`로 앱 시작에 성공했다. 테스트는 H2의 `create-drop`으로 저장·예약·취소 규칙을 확인한다. 전체 Gradle build(기존 테스트 포함)와 SpotBugs가 통과했다. 실제 MySQL에서 동시 주문 잠금 동작은 접수 API 연결 전에 검증해야 한다.
