# 대결 상세의 참가자별 전략·매매 요약 (#251)

## 계약

`GET /api/v2/users/me/competitions/{competition_id}`의 기존 필드를 유지하고 participants 각 항목에 다음을 추가한다.

| 필드 | 의미 | 값이 없는 경우 |
|---|---|---|
| executed_trade_count | 참가 계좌의 EXECUTED 상태 주문 개수. 매수·매도 포함, 미체결·취소 제외. 체결 행 수가 아닌 주문 수 | 0 |
| strategy | 생성 시 저장한 risk_level, preferred_themes, excluded_themes, additional_request | 설정 없는 사용자/기존 AI는 null |
| top_return_holding | 현재 보유분 중 평가 수익률이 가장 높은 종목의 stock_code, stock_name, return_percent | 미보유는 null |

수익률은 `(현재가 × 수량 - 총 취득원가) / 총 취득원가 × 100`이다. 반올림 전 값으로 비교하고 동률은 종목코드 오름차순, 응답은 소수점 4자리 HALF_UP이다. 모두 손실이면 가장 손실이 작은 종목이다. RedisMarketPrice의 저장 현재가와 기존 종목정보 공급자를 사용하며 신규 시세 수집은 하지 않는다. 같은 요청의 중복 종목은 가격·종목명을 재사용한다.

AI 간 대결은 설정 순서대로 AI 1~AI N을 반환한다. 기존 사용자 전용 상단 cumulative_return_percent는 null, daily_performances는 []다. 사용자 vs AI의 기존 확정 수익률·그래프 정책은 유지한다. 추가한 최고 수익률 보유 종목은 조회 시점 평가이므로 기존 마감 그래프와 기준 시점이 다르다. 완료·취소 대결에서도 현재 남아 있는 보유분 기준이며 종료 시점 스냅샷을 뜻하지 않는다.

시세·종목명 조회 실패는 503 HOLDINGS_DATA_UNAVAILABLE, 유효하지 않은 보유 원가·수량은 500 INVALID_HOLDINGS_DATA다. 미보유 null과 구분한다. 설정 누락·잘못된 AI 순서/인원은 500 INVALID_COMPETITION_PARTICIPANTS다. 소유권 확인에 실패하면 기존 404 COMPETITION_NOT_FOUND이며 시세 조회를 하지 않는다.

## 호출 흐름

CompetitionController.getCompetitionDetail(jwt, competitionId)
→ CompetitionService.getCompetitionDetail(userId, competitionId)
→ CompetitionDetailService.get(userId, competitionId)
→ 짧은 읽기 트랜잭션: 소유권·참가자·설정 검증, 주문 일괄 집계, 보유분·사용자 일별 기록을 값 객체로 복사
→ 트랜잭션 종료
→ Redis 현재가 평가·최고 종목 선택·종목명 조회
→ 참가자별 DTO와 기존 공통 필드 조립

생성 API, DB 스키마, AI 실행·자동 시작·취소·결과 API는 이 변경에 포함하지 않는다. FE·AI 저장소는 변경하지 않는다. 스윙·모멘텀 같은 신규 전략 분류는 생성하지 않는다.
