# 대결 상세의 참가자별 전략·매매 요약 (#251)

## 계약

`GET /api/v2/users/me/competitions/{competition_id}`의 기존 필드를 유지하고 participants 각 항목에 다음을 추가한다.

| 필드 | 의미 | 값이 없는 경우 |
|---|---|---|
| executed_trade_count | 참가 계좌의 EXECUTED 상태 주문 개수. 매수·매도 포함, 미체결·취소 제외. 체결 행 수가 아닌 주문 수 | 0 |
| strategy | 생성 시 저장한 risk_level, preferred_themes, excluded_themes, additional_request | 설정 없는 사용자/기존 AI는 null |
| top_return_holding | 현재 보유분 중 평가 수익률이 가장 높은 종목의 stock_code, stock_name, return_percent | 미보유는 null |

수익률은 `(현재가 × 수량 - 총 취득원가) / 총 취득원가 × 100`이다. 반올림 전 값으로 비교하고 동률은 종목코드 오름차순, 응답은 소수점 한 자리 HALF_UP이다. 모두 손실이면 가장 손실이 작은 종목이다. RedisMarketPrice의 저장 현재가와 기존 종목정보 공급자를 사용하며 신규 시세 수집은 하지 않는다. 같은 요청의 중복 종목은 가격·종목명을 재사용한다.

AI 간 대결은 설정 순서대로 AI 1~AI N을 반환한다. 두 유형 모두 최상위 수익률·그래프는 제거하고 participants 내부 값을 사용한다. 현재 자산·수익률과 날짜별 확정 그래프를 구분한다. 최고 수익률 보유 종목은 조회 시점 평가이며 완료·취소 후에도 종료 시점 스냅샷을 뜻하지 않는다.

시세·종목명 조회 실패는 503 HOLDINGS_DATA_UNAVAILABLE, 유효하지 않은 보유 원가·수량은 500 INVALID_HOLDINGS_DATA다. 미보유 null과 구분한다. 설정 누락·잘못된 AI 순서/인원은 500 INVALID_COMPETITION_PARTICIPANTS다. 소유권 확인에 실패하면 기존 404 COMPETITION_NOT_FOUND이며 시세 조회를 하지 않는다.

## 호출 흐름

아래 패키지명은 `com.stock_spoon.river_be`를 기준으로 한다.

```text
GET /api/v2/users/me/competitions/{competitionId} (본문 없음)
  → competition.controller.CompetitionController.getCompetitionDetail(jwt, competitionId)
    → JWT subject를 Long userId로 변환
  → competition.service.CompetitionService.getCompetitionDetail(userId, competitionId)
  → competition.service.CompetitionDetailService.get(userId, competitionId)
    → read.execute(...): 읽기 전용 트랜잭션 시작
      → CompetitionDetailService.snapshot(userId, competitionId)
        → competition.repository.CompetitionRepository.findByIdAndUserId(competitionId, userId)
          → 없거나 타인 소유: 404 COMPETITION_NOT_FOUND
        → competition.repository.CompetitionParticipantRepository
            .findAllByCompetitionIdOrderByIdAsc(competitionId)
        → competition.repository.CompetitionAgentRepository
            .findAllByParticipantCompetitionIdOrderByPositionAsc(competitionId)
        → 설정을 참가자 ID별 Strategy와 순서로 복사
        → 대결 유형별 참가자 수·AI 여부·설정 순서 검증
          → 잘못된 구성: 500 INVALID_COMPETITION_PARTICIPANTS
          → AI 간 대결: 설정 순서로 참가자 정렬
        → order.repository.OrderRepository.countByAccountsAndStatus(accountIds, EXECUTED)
        → holding.repository.HoldingRepository.findAllByAccountIds(accountIds)
        → 계좌별 횟수와 보유분을 Participant·Owned 값 객체로 복사
        → CompetitionDailyPerformanceRepository.findAllByParticipantCompetitionId(competitionId)
          → 참가자별로 묶고 날짜순으로 정렬해 저장 자산에서 한 자리 수익률 구성
        → CompetitionTradingHours.now(): 종료·취소 여부와 한국 날짜로 d_day 계산
        → Snapshot 반환
    → 읽기 전용 트랜잭션 종료
    → 참가자마다 CompetitionDetailService.topHolding(owned, priceCache, nameCache)
      → 수량·취득원가 검증: 실패 시 500 INVALID_HOLDINGS_DATA
      → market.RedisMarketPrice.currentPrice(stockCode): 요청 내 종목별 가격 재사용
      → 보유분 평가 수익률 계산·최고 종목 선택
        → 미보유: null
      → market.kiwoom.KiwoomMarketClient.stockDetails(stockCode).stockName()
        → 선택된 종목 이름도 요청 안에서 재사용
      → TopReturnHolding 반환
      → 시세·종목명 조회 실패: 503 HOLDINGS_DATA_UNAVAILABLE
    → ParticipantItem 목록과 CompetitionDetailResponse 구성
  → Controller가 HTTP 200과 JSON 반환

CompetitionException 발생 시
  → competition.exception.CompetitionExceptionHandler.handle(error)
  → 예외의 HTTP 상태와 {code, message} 반환
```

## 자연어 설명

대결 상세 GET 요청이 들어오면 `competition.controller` 패키지의 `CompetitionController.getCompetitionDetail(jwt, competitionId)`가 호출된다. 인증된 사용자의 JWT는 `jwt`, URL의 대결 ID는 `competitionId` 인자로 전달되며 요청 본문은 받지 않는다. 컨트롤러는 `jwt.getSubject()`를 `Long` 사용자 ID로 변환해 `CompetitionService.getCompetitionDetail(userId, competitionId)`에 넘긴다. 이 메서드는 같은 두 값을 `CompetitionDetailService.get(userId, competitionId)`로 전달한다.

`CompetitionDetailService.get()`은 읽기 전용 `TransactionTemplate`인 `read.execute()` 안에서 `snapshot(userId, competitionId)`을 실행한다. `snapshot()`은 `CompetitionRepository.findByIdAndUserId(competitionId, userId)`로 본인 소유 대결부터 찾는다. 존재하지 않거나 타인 소유라면 404로 종료하므로 참가 계좌의 시세를 조회하지 않는다. 소유권이 확인되면 `CompetitionParticipantRepository.findAllByCompetitionIdOrderByIdAsc()`로 참가자와 연결 계좌를 읽고, `CompetitionAgentRepository.findAllByParticipantCompetitionIdOrderByPositionAsc()`로 저장된 전략을 읽는다. 투자 성향·관심 테마·제외 테마·추가 요청은 참가자 ID별 `Strategy`로 복사한다.

이어서 대결 유형에 맞는 참가자 구성을 검사한다. 사용자 vs AI는 사용자 1명과 AI 1명이어야 한다. AI 간 대결은 설정한 AI 수와 실제 참가자 수가 일치하고, 전원이 AI이며, 각 참가자의 전략과 0부터 N-1까지의 설정 순서가 있어야 한다. 잘못된 구성은 `INVALID_COMPETITION_PARTICIPANTS`로 거절한다. 정상적인 AI 간 대결이면 설정 순서로 참가자를 정렬하고 화면 이름을 `AI 1`부터 부여한다.

각 참가자의 계좌 ID를 모아 `OrderRepository.countByAccountsAndStatus(accountIds, Order.Status.EXECUTED)`에 전달하면 계좌별 체결 완료 주문 수를 한 번에 얻는다. `HoldingRepository.findAllByAccountIds(accountIds)`에서는 보유 종목코드·수량·총 취득원가를 읽는다. 이 결과를 계좌 ID로 묶어 참가자별 `Participant`와 보유분별 `Owned` 값 객체에 넣는다. 주문이 없는 계좌의 횟수는 0이며, 저장된 전략이 없는 참가자의 전략은 null이다.

두 유형 모두 CompetitionDailyPerformanceRepository.findAllByParticipantCompetitionId(competitionId)로 대결의 기록을 읽어 참가자별·날짜별로 정렬한다. 응답 수익률은 저장된 일별 자산과 해당 계좌 초기금으로 계산하며 한 자리로 반올림한다. 기록이 없는 참가자는 빈 배열이다. 완료·취소는 d_day=null이고, 나머지는 한국 날짜 기준 비음수 남은 날짜다. DB 조회가 끝나면 Snapshot의 값 객체만 트랜잭션 밖으로 전달한다.

그다음 `get()`은 참가자별 `topHolding(owned, priceCache, nameCache)`을 호출한다. 이 메서드는 보유 수량과 원가를 검사하고, `RedisMarketPrice.currentPrice(stockCode)`로 읽은 현재가를 이용해 각 종목의 평가 수익률을 계산한다. 반올림 전 수익률이 가장 높은 종목을 선택하며 동률이면 종목코드가 작은 쪽을 고른다. 보유분이 없으면 null을 반환한다. 선택된 종목만 `KiwoomMarketClient.stockDetails(stockCode)`로 이름을 확인하고 `TopReturnHolding`을 만든다. 가격과 종목명은 요청별 맵에 보관해 여러 참가자가 같은 종목을 보유해도 재사용한다. 이 조회는 DB 트랜잭션이 끝난 뒤 수행한다.

마지막으로 `get()`은 기존 참가자 식별 정보에 횟수·전략·최고 수익률 종목을 붙인 `ParticipantItem` 목록을 만들고, 공통 대결 정보와 합쳐 `CompetitionDetailResponse`를 반환한다. 서비스와 컨트롤러를 거쳐 HTTP 200 JSON으로 전달된다. 중간에 발생한 `CompetitionException`은 `competition.exception` 패키지의 `CompetitionExceptionHandler.handle(error)`가 받아 지정된 HTTP 상태와 `code`, `message`로 반환한다. 시세·종목명 실패는 503으로 전달하므로 미보유를 뜻하는 null과 구분된다.

생성 API, DB 스키마, AI 실행·자동 시작·취소는 이 변경에 포함하지 않는다. 결과 API의 공통 구조는 [상세·결과 통일 계약](competition-response-unification.md)을 참고한다. FE·AI 저장소는 변경하지 않는다. 스윙·모멘텀 같은 신규 전략 분류는 생성하지 않는다.
