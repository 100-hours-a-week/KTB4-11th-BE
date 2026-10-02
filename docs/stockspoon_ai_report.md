# AI 매매 리포트 상세 조회 (v1)

구현 순서와 근거는 [구현 과정](stockspoon_ai_report_flow.md)을 참고한다.

GET `/api/v1/accounts/{account_id}/orders/{order_id}/ai-report`, 본문 없음, 정상 200.
사용자 access_token 쿠키로 본인의 활성 계좌 AI 주문을 조회한다.
미인증 401, 타인/없는/비활성 계좌 403, 주문·리포트 없음 또는 USER 주문 404.

주문 생성 reason은 필수 문자열(공백 불가, 최대 100,000 UTF-16 코드 단위)이며
주문과 1:1인 ai_order_reports에 같은 트랜잭션으로 저장한다.

## 응답 예시: 체결된 매도

```json
{
  "message": "success",
  "order_id": 104,
  "order_side": "sell",
  "stock_code": "000660",
  "stock_name": "[더미] 종목명",
  "report_status": "completed",
  "decided_at": "2026-09-03T14:20:00+09:00",
  "summary": "목표 수익률에 도달했고 상승 흐름이 약해져 매도했어요.",
  "reasoning": [],
  "execution": {
    "executed_at": "2026-09-03T14:21:00+09:00",
    "execution_price": 196000,
    "execution_quantity": 2,
    "trade_amount": 392000
  },
  "buy_analysis": null,
  "sell_analysis": {
    "trade_result": {
      "average_buy_price": 186600,
      "holding_days": 0,
      "realized_pnl": 18800,
      "realized_return_percent": 5.0375,
      "target_return_percent": 0,
      "target_reached": false,
      "stop_loss_triggered": false
    },
    "buy_decision": {"buy_report_id": 0, "summary": "[더미] 매수 당시 판단"},
    "holding_changes": [
      {"observed_at": "2026-09-03T14:20:00+09:00", "summary": "[더미] 보유 중 변화"}
    ],
    "sell_decision": "[더미] 매도 판단",
    "expectation_vs_outcome": {
      "expected_return_min_percent": 0,
      "expected_return_max_percent": 0,
      "summary": "[더미] 예상과 결과"
    }
  }
}
```

summary는 실제 AI 텍스트 원문이다. report_status=completed는 원문 저장 완료를 뜻한다.
매수는 sell_analysis=null, buy_analysis는 문서의 예시와 같이 null이다.
매수 상세 분석의 객체 형식은 공유 문서에 정의되지 않아 임의 구조를 만들지 않았다.
체결이 없으면 execution=null이다. 매도 분석 구조는 미체결 매도에서도 더미 값으로 존재한다.
체결의 실현손익이 누락된 매도에서는 실제 계산이 불가능한 결과도 임시 0이므로 개선 대상이다.

execution은 실제 체결 수량·금액 합, 수량 가중 평균가격, 마지막 체결 시각을 반환한다.
평균 가격과 실현수익률은 소수점 4자리 HALF_UP이다. JSON 숫자 표기의 소수점 자릿수는
시트 예시와 다를 수 있다. 실현손익은 합산하고 취득원가=매도금액−실현손익으로 역산한다.
원가 복원은 저장된 손익의 소수점 2자리 정밀도에 한정된다.
상세 조회의 날짜는 문서와 같이 +09:00이며, 주문 생성 응답의 기존 UTC Z 형식은 유지한다.

## 응답에서 제거한 필드와 근거

최상위 report_id는 제거했다. 주문과 리포트가 1:1이므로 API에서는 order_id로 식별한다. DB 내부 report_id PK와 문서에 존재하는 buy_decision.buy_report_id는 유지한다.

주문 목록 화면은 체결 완료된 주문의 근거만 열도록 한다는 사용자 정책에 따라
상세 조회의 order_status를 제거했다. 표시 대상이 이미 체결 완료라 화면에 중복 정보를 줄 필요가 없다.
주문 생성 응답의 order_status는 기존 계약대로 유지한다.

execution.execution_count도 제거했다. 화면은 집계된 평균 가격·수량·총 거래금액만 필요하며,
행 개수는 집계 결과 계산에 필수인 입력도 아니다. 수량 가중 평균과 실현손익 합산은
체결 행 목록으로 계속 수행하므로 건수 필드 제거가 계산 정확도에 영향을 주지 않는다.
체결 건수 표시는 실제 제품 요구가 생길 때 추가할 수 있다.

이번 변경은 응답 필드와 문서에 한정된다. 체결 완료 주문만 노출하는 목록 필터를 새로 구현하거나,
상세 조회에서 미체결 주문을 거절하는 검사까지 추가한 것은 아니다.
미체결 직접 조회는 기존처럼 execution=null을 반환한다.

## 개선 사항: 더미 데이터 교체

더미는 응답 조립에서만 만들고 DB에는 저장하지 않는다. 숫자 0·false는 실제 결과를 뜻하지 않는다.
buy_report_id=0은 연결 가능한 실제 리포트 ID가 아니다. FE에서 상세 링크로 사용하지 않는다.
판단 흐름은 아래 reasoning 계약에 따라 입력 순서대로 영구 저장한다.

| 필드 | 현재 임시 값 | 실제 데이터로 교체할 작업 |
|---|---|---|
| stock_name | 신규 주문은 AI가 제공한 저장값, 기존 이름 없는 주문은 [더미] 종목명 | 새 주문은 외부 조회 없이 저장값 반환 |
| decided_at | 주문 생성 시각 | AI 판단 시각 수신·저장. 주문 시각과 의미가 다름 |
| trade_result.holding_days | 0 | 추가 매수·부분 매도에 대한 기간 정책 및 체결 이력 조회 |
| trade_result.target_return_percent | 0 | 거래 당시 목표 수익률 스냅샷 저장 |
| trade_result.target_reached | false | 목표 스냅샷과 실제 결과 비교 |
| trade_result.stop_loss_triggered | false | AI 판단 조건/발동 여부 수신·저장 |
| buy_decision | ID 0, [더미] 텍스트 | 매도와 관련 매수 리포트 연결 정책 |
| holding_changes | 주문 시각과 [더미] 텍스트 한 건 | 보유 중 분석 이력 수신·저장 |
| sell_decision | [더미] 매도 판단 | 매도 상세 분석 계약 확정 |
| expectation_vs_outcome | 예상 범위 0~0%, [더미] 텍스트 | 예상 수익률 수신·저장 및 실제 결과 비교 |
| buy_analysis | null | 매수 상세 객체 스키마 합의 후 응답 구현 |
| 미체결/손익 누락 매도의 결과 | 임시 0 | 미체결·결측값 표현을 FE와 합의. 실제 체결 값으로 교체 |

## DB 적용

기존 MySQL DB에는 새 애플리케이션 배포 전에 `db/ai_order_reports.sql`을 1회 적용한다.
기존 AI 주문의 비어 있지 않은 decision_summary를 새 리포트로 이관한다. 근거가 없는
기존 주문은 리포트가 없으므로 상세 조회 시 404다. 이전 컬럼은 복구용으로 남기지만
새 코드에서는 읽거나 쓰지 않는다. 테이블 생성과 이관은 자동 실행되지 않는다.
구버전 앱의 쓰기를 중단한 뒤 이관하고 새 버전을 배포해야 새 주문의 근거가 누락되지 않는다.
SQL은 MySQL 8.4용이며 실제 운영 DB에는 이번 작업에서 적용하지 않았다.


## AI 종목명·판단 흐름 계약

주문 생성의 stock_name은 선택 문자열이며 생략 또는 null을 허용한다. 값을 보내는 경우 공백은 불가하며 최대 255 Java UTF-16 코드 단위까지 허용한다.
AI가 제공한 원문을 ai_order_reports.stock_name에 저장하고, 주문 생성 응답과 리포트 상세 조회의
stock_name으로 그대로 반환한다. 이 이름을 반환하기 위해 외부 종목정보를 조회하지 않는다.
기존 리포트의 이름은 SQL로 추정해 채우지 않으며, 이름이 NULL이면 기존 상세 응답의
"[더미] 종목명"을 유지한다.

reason은 기존처럼 필수이며 상세 조회에서는 summary가 된다.
reasoning은 선택 항목인 순서 있는 [{label, body}] 배열이다. label은 공백 불가·최대 255자,
body는 공백 불가·최대 16,000자(Java UTF-16 기준)이고 null 항목은 거절한다.
생략·null·빈 목록은 빈 배열로 저장/반환한다. API 필드명은 thoughts가 아니라 reasoning이다.
매수·매도, 지정가·시장가 모두 같은 리포트 계약을 사용한다.

reasoning은 ai_report_reasoning 테이블에 report_id와 reasoning_index로 순서를 보존하여
주문·리포트와 같은 트랜잭션으로 저장한다. 상세 조회에는 reasoning을 그대로 반환한다.
focused_news·decision_summary를 reason에 JSON 문자열로 저장하는 계약은 추가하지 않았다.

기존 DB에는 새 앱 배포 전에 db/ai_report_reasoning.sql을 1회 적용해야 한다.
이 SQL은 자동 실행되지 않으며 운영 DB 적용은 별도 배포 작업이다.
