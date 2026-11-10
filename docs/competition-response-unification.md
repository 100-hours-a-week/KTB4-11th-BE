# 대결 상세·결과 공통 응답 계약 (#255)

## 전환 안내

기존 v2 경로를 유지하고 user_vs_ai와 ai_vs_ai를 참가자별 공통 구조로 통일한다. 아래 필드 구조는 MockMvc 계약 테스트의 실제 직렬화로 검증했다. 예시 값은 각 계좌 초기금 300만 원, 첫 참가자만 100만 원 매수 후 16.65% 상승한 상황으로 정리했다. 그래프는 마지막 하루만 표시한다. 운영 API를 호출해 수집한 데이터는 아니다. 원본 테스트 JSON은 build/reports/competition-response-*.json에 남긴다.

FE는 두 유형 모두 participants 내부에서 성적·그래프를 읽어야 한다. 기존 최상위 필드를 제거하는 호환성 변경이므로 FE 담당자와 배포 전환 시점을 맞춘다. 이 BE 작업에서 FE·AI 저장소를 수정하지 않는다.

| API | 제거하는 최상위 필드 |
|---|---|
| 상세 | cumulative_return_percent, daily_performances |
| 결과 | user_final_return_percent, ai_final_return_percent, daily_performances |

결과 participants에는 final_asset_value, final_return_percent, rank, executed_trade_count, strategy, daily_performances를 두 유형 모두 제공한다. winner_participant_ids는 배열, winner는 표시 이름 문자열이다. 공동 우승은 참가자 응답 순서로 이름을 ", "로 연결한다. 예: "AI 1, AI 2", "나, AI". ID 배열도 같은 우승자 순서를 따른다.

user_vs_ai는 저장된 별도 전략이 없어 두 참가자의 strategy가 null이다. ai_vs_ai는 저장된 risk_level(1=안정형, 5=성장형), 관심/제외 테마, 추가 요청을 반환한다. 매매 횟수는 기존 상세와 같은 EXECUTED 주문 수이며 미체결·취소 주문은 제외한다.

## 수익률과 날짜

- 현재·최종·일별·최고 종목 수익률은 이 두 API에서 모두 소수점 한 자리 HALF_UP 숫자로 반환한다. 5.55는 5.6, -5.55는 -5.6이다.
- 현재 자산과 최고 종목은 조회 시 평가다. 결과 성적과 일별 기록은 저장된 자산에서 계산하며 현재 시세를 조회하지 않는다.
- 응답 수익률은 저장된 자산과 초기금으로 한 번만 반올림한다. DB 수익률 4자리 저장 정밀도와 순위 비교는 바꾸지 않는다. 반올림된 표시 수익률이 같아도 정확한 자산 비율이 다르면 순위는 다르다.
- 그래프는 참가자별 날짜 오름차순이다. 누락일을 0으로 만들지 않고 기록이 없으면 []다.
- 완료·취소 d_day는 null이다. 미완료 종료일 당일은 0으로 "오늘 종료"를 표시한다. 기존 비음수 일수 계산은 유지하므로 종료일이 지난 정산 대기는 end_date/state도 함께 확인해 "정산 중"으로 구분한다.
- 결과의 최고 수익률 종목 추가와 종료 시점 보유분 스냅샷 저장은 이번 계약에 포함하지 않는다.

## 실제 직렬화 예시

### 상세: 사용자 vs AI

```json
{
  "competition_id": 1,
  "competition_name": "응답 통일",
  "competition_type": "user_vs_ai",
  "state": "in_progress",
  "start_date": "2026-11-02",
  "end_date": "2026-11-06",
  "d_day": 0,
  "participants": [
    {
      "participant_id": 101,
      "account_id": 201,
      "participant_type": "user",
      "display_name": "나",
      "executed_trade_count": 1,
      "strategy": null,
      "top_return_holding": {
        "stock_code": "000660",
        "stock_name": "SK하이닉스",
        "return_percent": 16.7
      },
      "cumulative_return_percent": 5.6,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 5.6
        }
      ],
      "total_assets": 3166500
    },
    {
      "participant_id": 102,
      "account_id": 202,
      "participant_type": "ai",
      "display_name": "AI",
      "executed_trade_count": 0,
      "strategy": null,
      "top_return_holding": null,
      "cumulative_return_percent": 0.0,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 0.0
        }
      ],
      "total_assets": 3000000
    }
  ]
}
```

### 상세: AI 간 대결

```json
{
  "competition_id": 2,
  "competition_name": "응답 통일",
  "competition_type": "ai_vs_ai",
  "state": "in_progress",
  "start_date": "2026-11-02",
  "end_date": "2026-11-06",
  "d_day": 0,
  "participants": [
    {
      "participant_id": 103,
      "account_id": 203,
      "participant_type": "ai",
      "display_name": "AI 1",
      "executed_trade_count": 1,
      "strategy": {
        "risk_level": 5,
        "preferred_themes": [
          "반도체"
        ],
        "excluded_themes": [],
        "additional_request": ""
      },
      "top_return_holding": {
        "stock_code": "000660",
        "stock_name": "SK하이닉스",
        "return_percent": 16.7
      },
      "cumulative_return_percent": 5.6,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 5.6
        }
      ],
      "total_assets": 3166500
    },
    {
      "participant_id": 104,
      "account_id": 204,
      "participant_type": "ai",
      "display_name": "AI 2",
      "executed_trade_count": 0,
      "strategy": {
        "risk_level": 1,
        "preferred_themes": [
          "반도체"
        ],
        "excluded_themes": [],
        "additional_request": ""
      },
      "top_return_holding": null,
      "cumulative_return_percent": 0.0,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 0.0
        }
      ],
      "total_assets": 3000000
    }
  ]
}
```

### 결과: 사용자 vs AI

```json
{
  "competition_id": 1,
  "competition_name": "응답 통일",
  "competition_type": "user_vs_ai",
  "state": "completed",
  "start_date": "2026-11-02",
  "end_date": "2026-11-06",
  "d_day": null,
  "winner": "나",
  "winner_participant_ids": [
    101
  ],
  "participants": [
    {
      "participant_id": 101,
      "participant_type": "user",
      "account_id": 201,
      "display_name": "나",
      "final_asset_value": 3166500,
      "final_return_percent": 5.6,
      "rank": 1,
      "executed_trade_count": 1,
      "strategy": null,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 5.6
        }
      ]
    },
    {
      "participant_id": 102,
      "participant_type": "ai",
      "account_id": 202,
      "display_name": "AI",
      "final_asset_value": 3000000,
      "final_return_percent": 0.0,
      "rank": 2,
      "executed_trade_count": 0,
      "strategy": null,
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 0.0
        }
      ]
    }
  ]
}
```

### 결과: AI 간 대결

```json
{
  "competition_id": 2,
  "competition_name": "응답 통일",
  "competition_type": "ai_vs_ai",
  "state": "completed",
  "start_date": "2026-11-02",
  "end_date": "2026-11-06",
  "d_day": null,
  "winner": "AI 1",
  "winner_participant_ids": [
    103
  ],
  "participants": [
    {
      "participant_id": 103,
      "participant_type": "ai",
      "account_id": 203,
      "display_name": "AI 1",
      "final_asset_value": 3166500,
      "final_return_percent": 5.6,
      "rank": 1,
      "executed_trade_count": 1,
      "strategy": {
        "risk_level": 5,
        "preferred_themes": [
          "반도체"
        ],
        "excluded_themes": [],
        "additional_request": ""
      },
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 5.6
        }
      ]
    },
    {
      "participant_id": 104,
      "participant_type": "ai",
      "account_id": 204,
      "display_name": "AI 2",
      "final_asset_value": 3000000,
      "final_return_percent": 0.0,
      "rank": 2,
      "executed_trade_count": 0,
      "strategy": {
        "risk_level": 1,
        "preferred_themes": [
          "반도체"
        ],
        "excluded_themes": [],
        "additional_request": ""
      },
      "daily_performances": [
        {
          "date": "2026-11-06",
          "cumulative_return_percent": 0.0
        }
      ]
    }
  ]
}
```

## 호출 흐름과 경계

상세의 실제 호출·DB/시세 경계는 [상세 흐름](competition-detail-summary.md), 결과는 [대결 기능 8절](competition-creation.md#8-대결-결과-조회)을 따른다. 두 API 모두 JWT 소유권을 유지한다. 결과는 미완료 409, 타인·없는 대결 404이며 조회로 DB 기록을 수정하지 않는다.

이 응답 통일 작업 자체에는 추가 운영 DB 컬럼·테이블이 없다. 이후 추가된 AI 대결 준비 완료·자동 시작 및 필요한 DB 컬럼은 [시작 계약](competition-ai-start.md)을 따른다. 전략 전송과 참가자별 리포트도 각각의 최신 문서를 확인한다.
