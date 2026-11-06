# 대결 상세·결과 응답 통일 확정안

## 목적과 범위

user_vs_ai와 ai_vs_ai의 상세·결과를 같은 참가자별 구조로 제공한다. 2026-11-06 대화에서 결과 예시와 상세 변경안을 확정했다. 계획·이슈 #255 생성 후 사용자가 구현 진행을 승인했다.

대상은 GET /api/v2/users/me/competitions/{competition_id} 및 GET /api/v2/users/me/competitions/{competition_id}/result다. URL·HTTP 메서드·인증과 소유권 정책은 유지한다. FE·AI 코드는 수정하지 않는다. 생성·주문·정산 방식, SQS·리포트 연동, 자동 시작 활성화는 제외한다.

## 상세 계약

- 최상위 cumulative_return_percent, daily_performances를 제거한다.
- participants에 있는 participant_id, account_id, participant_type, display_name, executed_trade_count, strategy, top_return_holding, total_assets, cumulative_return_percent, daily_performances는 두 유형 모두 유지한다.
- total_assets와 참가자 수익률은 현재 평가 기준이며 참가자 그래프는 날짜별 확정 기록이다.
- user_vs_ai는 현재 저장된 전략이 없으므로 사용자와 AI 모두 strategy=null이다. ai_vs_ai는 저장 전략을 반환한다.
- 보유 종목이 없으면 top_return_holding=null이다. 시세 조회 실패와 구분하는 기존 오류 정책을 유지한다.
- 상세에는 winner·rank를 추가하지 않는다.

## 결과 계약

- 최상위 winner는 단독 우승자의 display_name 문자열이다. 사용자 우승은 "나", user_vs_ai AI 우승은 "AI", ai_vs_ai AI 우승은 "AI 1" 등이다. 배열이나 참가자 ID로 타입을 바꾸지 않는다.
- winner_participant_ids는 두 유형 모두 우승 참가자 ID 배열이다.
- 최상위 user_final_return_percent, ai_final_return_percent, daily_performances를 제거한다.
- participants에는 participant_id, participant_type, account_id, display_name, final_asset_value, final_return_percent, rank, executed_trade_count, strategy, daily_performances를 두 유형 모두 제공한다.
- 기존 user_vs_ai에도 final_asset_value, final_return_percent, rank, daily_performances를 실제 값으로 채운다. 이전 구현의 null을 유지하지 않는다.
- strategy는 저장된 값만 반환하고 없으면 null이다. 새로운 전략·요약 문구를 임의로 생성하지 않는다.
- executed_trade_count는 기존 상세와 동일한 체결 완료 주문 집계 기준을 사용한다.
- final_asset_value와 final_return_percent는 저장된 종료 확정 성적이다. 결과 조회에서 현재 시세로 재계산하지 않는다.
- 참가자별 그래프는 날짜 오름차순, 기록이 없으면 빈 배열이다. 기록 누락을 임의의 0%로 채우지 않는다.
- 순위는 반올림 전 자산/초기금 비율을 사용하고 공동 순위는 1,1,3을 유지한다.
- 승인된 결과 예시에는 top_return_holding이 없다. 종료 시점 최고 종목의 별도 저장·반환은 이번 범위에 넣지 않는다.

## d_day 최종 합의

앞서 결과를 0으로 반환하자는 제안은 이후 대화에서 변경되었다. completed/cancelled는 null을 유지하고, 종료일 당일 대기·진행 중의 0은 "오늘 종료" 표시로 사용한다. 종료일이 지났지만 정산 전인 경우 현재 날짜·end_date·state를 함께 확인해 "정산 중"으로 구분한다. 기존 비음수 남은 날짜 계산을 임의로 음수나 -1로 바꾸지 않는다. 이 작업으로 목록 API의 계약은 변경하지 않는다.

## 접근 방식

기존 별도 최상위 사용자/AI 필드를 계속 유지하면 두 형식을 FE가 계속 분기해야 하므로 채택하지 않는다. 새 v3 경로를 만드는 대신 사용자가 확정한 기존 v2 응답을 변경한다. 이는 기존 필드 삭제와 winner 의미 변경을 포함하므로 FE 담당자와 전환 시점을 맞춰야 한다. BE 작업자가 FE 코드를 대신 수정하지 않는다.

저장 스키마·Winner enum은 유지하고 표시 이름은 응답을 만들 때 참가자와 연결한다. 추가 DDL은 현재 계획에 없다. 필요성이 확인되면 운영 변경 사항은 별도로 보고한다.

## 추가 확정: 표시 정밀도와 공동 우승

- 공동 우승의 winner는 응답 참가자 순서대로 표시 이름을 ", "로 연결한다. 예: "AI 1, AI 2", "나, AI". winner_participant_ids도 같은 우승자 순서로 반환한다.
- 이 상세·결과 API에서 현재·최종·일별·종목 수익률은 모두 소수점 한 자리 HALF_UP 숫자로 반환한다. 전역 직렬화 설정이나 다른 API는 변경하지 않는다.
- 확정 성적과 일별 기록은 저장된 자산/초기금으로 응답 수익률을 한 번 계산한다. 저장된 4자리 수익률을 다시 1자리로 반올림하여 경계가 달라지는 문제를 피한다. 현재 시세로 확정 자산을 평가하지 않는다.
- DB 수익률 4자리 저장 정밀도와 반올림 전 순위 비교는 유지한다. 표시 수익률이 같아도 실제 비율이 다르면 공동 순위가 아니다.
