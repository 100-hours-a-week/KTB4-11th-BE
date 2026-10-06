# AI 매매 리포트 상세 조회

GET /api/v1/accounts/{account_id}/orders/{order_id}/ai-report

인증된 사용자의 활성 계좌에 속한 AI 주문의 근거와 체결 결과를 조회한다.
계좌 접근 권한 검사는 기존 구현을 유지한다. 타인 계좌는 403 FORBIDDEN_ACCOUNT,
주문·AI 리포트가 없거나 AI 주문이 아닌 경우 404 AI_REPORT_NOT_FOUND다.
체결된 주문만 조회하는 정책을 적용한다. 미체결·취소 또는 체결 이력이 없는 주문은 404 AI_REPORT_NOT_FOUND를 반환한다.

## 응답 필드

| 필드 | 출처 |
|---|---|
| order_id/order_side/stock_code | 주문 |
| stock_name | AI가 주문 생성에 보낸 저장 종목명 |
| reason | 저장된 AI 판단 근거 |
| reasoning | 저장 순서의 label/body 배열 |
| holding_weight_limit_percent | 매수 리포트의 저장 Double, 매도는 null |
| holding_weight_after_trade_percent | 매수 체결 시점에 저장한 평가 비중, 매도는 null |
| execution | 해당 주문의 실제 체결 이력 집계 |
| sell_result | 매도 결과, 매수는 null |

기존 message/report_status/decided_at/summary/buy_analysis/sell_analysis 및 매도 하단
매수 판단·보유 변화·예상 결과 더미 구조는 제거한다. JSON null 키는 응답에 포함한다.
종목명·기준값·계산 불가 값은 null, reasoning이 없으면 []를 반환한다. 임의 더미는 사용하지 않는다.

## 체결 및 손익 계산

- execution_quantity: 모든 체결 수량의 합.
- trade_amount: 체결 가격×수량의 합.
- execution_price: 총 금액/총 수량의 수량 가중평균, 기존 소수4자리 HALF_UP.
- executed_at: 마지막 체결시각, Asia/Seoul 오프셋.
- realized_pnl: 저장된 체결별 실현손익 합계.
- 매도 원가: 총 금액-실현손익.
- average_buy_price: 매도 원가/매도 수량.
- realized_return_percent: 실현손익/매도 원가×100. 체결별 수익률 단순 평균 금지.

기존 체결 엔진은 수수료·세금을 차감하지 않는다. 저장 손익이 소수2자리이므로
역산 원가와 평균매수가에는 해당 반올림 정밀도 제한이 있다.
손익이 하나라도 없으면 관련 손익/평균매수가/수익률을 추정하지 않는다.
원가가 양수가 아니면 평균매수가·수익률을 계산하지 않는다.
비용 차감 전 손익 및 기존 반올림 기준을 유지한다. 누락 손익은 null로 반환한다.

## 보유기간

매도 마지막 체결까지의 계좌·종목 체결을 시각·ID 순서로 읽는다.
현재 보유 구간 최초 매수일과 매도일의 서울 날짜 차이로 holding_days를 계산한다.
당일0일, 추가매수는 시작일 유지, 전량매도 후 재매수는 시작일 초기화.
뒤에 발생한 거래는 과거 리포트 계산에서 제외한다.
이력이 부족하여 보유량이 음수가 되거나 최초 매수를 확인할 수 없으면 추정하지 않는다.

## 거래 직후 평가 비중

매수의 자산 변경·체결·리포트 저장과 같은 트랜잭션에서 계산한다.
비중=해당 종목 평가액/(체결 후 현금+모든 보유종목 평가액)×100.
해당 종목의 전체 보유수량에는 이번 주문 평균 체결가를 적용한다.
다른 종목은 KiwoomStockStream.latest에 이미 보관된 현재가를 사용한다.
별도 REST 조회·구독 추가는 하지 않는다. 이 계산은 캐시에 보관된 가격 기준 평가다.
필요한 다른 종목 시세가 없거나 가격이 유효하지 않으면 비중만 null로 남기고 주문을 실패시키지 않는다.
비중은 소수4자리 HALF_UP을 적용한 Double로 저장한다.
후속 거래나 시세 변동 시 조회 API에서 다시 계산하지 않는다.
저장된 상한을 초과하더라도 표시용이므로 주문을 거절하지 않는다.

## 손실 제한 및 목표

sell_result.stop_loss_triggered는 AI 입력 is_lower_triggered를 저장한 Boolean이다.
실제 false와 누락을 구분하며 BE가 손익으로 손실 제한 결과를 다시 판단하지 않는다.
매도 목표 수익률은 10%로 고정하여 target_return_percent=10.0을 반환한다.
target_reached는 저장 실현손익×100 >= 매도 원가×10으로 판단한다. 표시 수익률 반올림 전 금액 기준이며, 손익 누락 또는 원가가 0 이하이면 null이다. 기존 매도 리포트 조회에도 동일 기준을 적용한다.

## DB 적용 순서

1. db/ai_report_reasoning.sql: stock_name 및 ai_report_reasoning.
2. db/ai_report_order_inputs.sql: 상한 Double·손실 제한 Boolean.
3. db/ai_report_holding_weight.sql: 체결 시점 비중 Double.

SQL은 자동 실행되지 않는다. 기존 스키마를 확인한 뒤 미적용 변경만 적용한다.
기존 주문의 과거 비중을 현재 시세로 임의 채우지 않는다.
이번 상세조회 작업은 로컬/운영 DB에 SQL을 자동 적용하지 않는다.
