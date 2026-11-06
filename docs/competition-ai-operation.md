# AI 간 대결 계좌 운용 확장

## 지원 범위

BE 내부의 다수 AI 주문·체결 계좌 격리 검증, 일별 성과, 종료 정산, 취소, 상세·결과 조회를 확장한다. AI 설정 SQS 전송과 실제 시작 활성화는 후속 작업이다. 생성 요청은 바꾸지 않는다.

현재 AI 간 대결은 계속 READY로 생성된다. Entity.start와 자동 시작 조회의 AI 차단은 유지한다. 이번 통합 테스트만 이미 시작된 상태를 재현하므로 이 변경만 배포해도 AI 대결이 자동 운용되지는 않는다.

## API 추가 필드

GET /api/v2/users/me/competitions/{competition_id}

기존 participants 항목에 다음을 추가한다.

- total_assets: 현재 현금 + 보유분 현재 평가액, 원 단위 정수
- cumulative_return_percent: 초기금 대비 현재 수익률, 소수점 4자리 반올림
- daily_performances: 해당 참가자의 날짜별 종가 기준 확정 수익률 배열

예시 참가자:

```json
{
  "participant_id": 201,
  "account_id": 301,
  "participant_type": "ai",
  "display_name": "AI 1",
  "executed_trade_count": 18,
  "strategy": {"risk_level": 5, "preferred_themes": ["반도체"], "excluded_themes": [], "additional_request": ""},
  "top_return_holding": {"stock_code": "005930", "stock_name": "삼성전자", "return_percent": 12.0},
  "total_assets": 1040000,
  "cumulative_return_percent": 4.0,
  "daily_performances": [{"date": "2026-10-12", "cumulative_return_percent": 2.0}]
}
```

최상위 cumulative_return_percent와 daily_performances는 기존 사용자 기준이다. AI 간 대결에서는 각각 null, []를 유지하고 participants 내부 성적을 사용한다. 상세의 현재 평가액은 종료 후에도 시세에 따라 달라질 수 있으며 종료 확정 결과는 아래 result에서 조회한다.

GET /api/v2/users/me/competitions/{competition_id}/result

AI 간 대결에 winner_participant_ids를 추가하고 participants 각 항목에 final_asset_value, final_return_percent, rank, daily_performances를 제공한다. AI 표시는 생성 시 전략 순서를 유지한다. 공동 순위는 1,1,3 방식이다. 순위는 반올림된 수익률 대신 정확한 자산/초기금 비율로 비교한다. 기존 user_vs_ai의 필드와 의미는 유지한다. 기존 1:1 전용 user_final_return_percent, ai_final_return_percent는 AI 간 대결에서 null이고 최상위 daily_performances는 []다. AI 간 winner 문자열 ai/draw만으로 참가자를 식별하지 말고 winner_participant_ids를 사용한다.

POST /api/v2/users/me/competitions/{competition_id}/cancel

AI 2~4명 모두의 대기 주문을 취소하고 계좌를 비활성화한다. 자산·보유분·기록은 보존한다. 반복 취소는 기존처럼 성공하고 타인 대결은 404다.

## 기존 주문과 정산

기존 주문 메시지의 user_id/account_id 및 계좌 잠금·중복 방지 계약을 재사용한다. 별도의 participant_id를 주문 필수값으로 추가하지 않는다. 계좌와 대결 참가자의 기존 연결을 사용한다.

일별 기록은 각 참가자의 실제 체결을 모아 계산한다. 종료 시에는 모든 계좌를 ID 순으로 잠그고 종료일 종가로 평가하며, 최종 성적과 마지막 일별 기록·대기 주문 취소·계좌 비활성화를 같은 트랜잭션으로 처리한다. 한 계좌라도 평가 실패하면 전체를 롤백한다.

기존 종료 리포트 계약은 AI 한 명만 지원하므로 AI 간 정산에서는 리포트 Outbox를 생성하지 않는다. 기존 사용자 vs AI 리포트 발행은 유지한다. 다수 AI 리포트 생성·저장·조회는 후속 계약이 필요하다.

## 운영 DB

이번 운용 확장으로 추가하는 컬럼·테이블은 없다. 순위와 우승 참가자 ID는 기존 최종 자산으로 계산한다. 선행 생성 기능의 전략 테이블 등 기존 마이그레이션은 별도로 적용되어 있어야 한다. 운영 DB에는 접속하거나 DDL을 적용하지 않는다.
