# 대결 기능: 브랜치별 호출 흐름

각 기능은 **호출 흐름 → 전달값 표 → 계산·정책 예시 → 예외 처리** 순서로 읽는다.
메서드는 현재 코드 기준이다. 자동 실행 기능의 진입점은 스케줄러다.

| 작업 브랜치 | 기능 | 읽을 위치 |
|---|---|---|
| `feat/107-competition/create-competition` | 대결·계좌·참가자 생성 | [1. 대결 생성](#1-대결-생성) |
| 같은 브랜치 | 대기 대결 시작 | [2. 자동 시작](#2-자동-시작) |
| `feat/121-competition/competition-list` | 내 대결 전체 조회 | [3. 목록 조회](#3-목록-조회) |
| `feat/123-competition/completion-scheduler` | 결과 확정·완료 처리 | [4. 종료 정산](#4-종료-정산) |
| 같은 브랜치 | 마감 후 거래 차단 | [5. 주문 차단](#5-주문-차단) |

## 1. 대결 생성

### 호출 흐름

```text
POST /api/v2/users/me/competitions
    ↓ 인증·CSRF 검사, 요청 DTO의 @Valid 검증
CompetitionController.createCompetition()
    ↓ 사용자 ID + 요청 DTO
CompetitionService.createCompetition()
    ├─ UserRepository → 사용자 잠금 조회
    ├─ AccountRepository → 계좌명 중복 검사
    ├─ CompetitionRepository → 대결 저장
    ├─ AccountRepository → 사용자·AI 계좌 저장
    └─ CompetitionParticipantRepository → 대결과 두 계좌 연결 저장
    ↓ CompetitionCreateResponse
201 Created + JSON
```

### 단계별 전달값

| 단계 | 실제 메서드 | 입력 → 결과 |
|---|---|---|
| 요청 수신 | `CompetitionController.createCompetition(jwt, request)` | JWT·요청 DTO → 서비스가 반환한 응답 DTO |
| 업무 처리 | `CompetitionService.createCompetition(userId, request)` | 사용자 ID·요청 DTO → 생성 결과 |
| 사용자 조회 | `UserRepository.findLockedById(userId)` | 사용자 ID → 잠근 User Entity |
| 이름 검사 | `AccountRepository.existsByUserIdAndNameIgnoreCase(userId, name)` | 사용자 ID·이름 → 중복 여부 |
| 대결 저장 | `CompetitionRepository.save(competition)` | 새 대결 → ID가 발급된 대결 |
| 시작 판단 | `CompetitionTradingHours.isOpen(now)` / `Competition.start(startedAt)` | 현재 시각 → 장중이면 `IN_PROGRESS`, 장외면 `READY` 유지 |
| 계좌 저장 | `AccountRepository.save(account)` | 사용자·AI 계좌 각각 → 저장된 계좌 |
| 참가자 저장 | `CompetitionParticipantRepository.save(participant)` | 대결·계좌 연결 → 저장된 참가자 |
| 응답 구성 | `new CompetitionCreateResponse(...)` | 메시지·대결 ID·상태 → 응답 DTO |

### 처리 예시와 정책

평일 저녁에 `반도체 집중`, 초기 자금 `10,000,000원`으로 생성한 경우:

```text
대결 1: READY
사용자 계좌: 반도체 집중 / 자금 10,000,000원
AI 계좌: 내부 이름 사용 / 자금 10,000,000원
참가자 2개: 대결 1 ↔ 사용자 계좌 / 대결 1 ↔ AI 계좌
```

```json
{"message":"success","competition_id":1,"state":"ready"}
```

- JWT의 `sub`에서 사용자 ID를 읽는다. 온보딩 완료 사용자만 생성할 수 있다.
- v2는 `user_vs_ai`, `ai_participant_count = 1`이다.
- 시작일은 한국 시간 기준 오늘, 종료일과의 날짜 차이는 최소 3일이다.
- 대결명은 앞뒤 공백을 제거한 뒤 20자 이하이며, 같은 사용자의 일반·대결 계좌명과 중복될 수 없다.
- AI 계좌명은 `AI-{대결 ID의 36진수}`이며 충돌 시 접미사를 붙인다.
- BE 내부에서 계좌 Entity를 만들어 저장한다. 두 계좌의 자금은 각각 지급한다.
- 서비스의 `@Transactional`로 대결·계좌·참가자 저장을 한 묶음으로 처리한다.

### 예외 처리

```text
DTO 제약 위반 → 컨트롤러 메서드 실행 전 400
업무 규칙 위반 → CompetitionException → CompetitionExceptionHandler.handle()
              → 예외의 HTTP 상태 + code/message
저장 실패 → 생성 트랜잭션 전체 롤백
```

코드: [컨트롤러](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionController.java) · [서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java) · [예외 처리](../src/main/java/com/stock_spoon/river_be/competition/exception/CompetitionExceptionHandler.java)

## 2. 자동 시작

### 호출 흐름

```text
Spring 예약 실행: 기본 10초 간격
    ↓
CompetitionStartScheduler.startWaitingCompetitions()
    ├─ CompetitionTradingHours → 장이 열렸는지 확인
    └─ CompetitionRepository → 기간 내 READY 대결 잠금 조회
    ↓ 각 Competition.start()
트랜잭션 커밋 → IN_PROGRESS 저장
```

### 단계별 전달값

| 단계 | 실제 메서드 | 입력 → 결과 |
|---|---|---|
| 시간 확인 | `CompetitionTradingHours.now()` / `isOpen(now)` | 한국 시각 → 장중 여부 |
| 대상 조회 | `CompetitionRepository.findWaitingToStart(today)` | 오늘 날짜 → 기간 내 대기 대결 목록 |
| 상태 변경 | `Competition.start(startedAt)` | 실제 시작 시각 → 상태·`startedAt` 변경 |

스케줄러가 직접 Repository를 호출한다. 별도 시작 서비스와 HTTP 응답은 없다.

### 처리 예시와 정책

```text
토요일 생성 → READY
월요일 09:00 이후 스케줄 실행 → IN_PROGRESS
요청받은 종료일은 연장하지 않음
```

한국 시간 평일 09:00 이상, 15:30 미만을 장중으로 판단한다. 공휴일은 아직 고려하지 않는다.
DB 잠금으로 중복 시작을 막고, Entity 변경은 트랜잭션 커밋 때 저장한다. 별도 `save()`는 필요 없다.

### 실행 제외 조건

장이 닫혀 있으면 대결 조회를 생략한다. 이미 시작했거나 종료일이 지난 대결은 조회 대상에서 제외한다.
`READY` 상태로 기간이 끝난 대결의 만료 정책은 후속 작업이다.

코드: [시작 스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionStartScheduler.java)

## 3. 목록 조회

### 호출 흐름

```text
GET /api/v2/users/me/competitions
    ↓
CompetitionController.listCompetitions()
    ↓ 사용자 ID
CompetitionService.listCompetitions()
    ├─ CompetitionRepository → 내 대결 전체 조회
    ├─ CompetitionParticipantRepository → 사용자 참가자·계좌 조회
    └─ AccountService.returnPercents() → 현재 계좌별 수익률 계산
    ↓ CompetitionListResponse
200 OK + JSON
```

### 단계별 전달값

| 단계 | 실제 메서드 | 입력 → 결과 |
|---|---|---|
| 요청 수신 | `CompetitionController.listCompetitions(jwt)` | JWT → 서비스가 반환한 응답 DTO |
| 목록 구성 | `CompetitionService.listCompetitions(userId)` | 사용자 ID → 개수·대결 목록 DTO |
| 대결 조회 | `CompetitionRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId)` | 사용자 ID → 최신순 대결 전체 |
| 계좌 연결 조회 | `CompetitionParticipantRepository.findUserParticipants(userId)` | 사용자 ID → 사용자 참가자·계좌 목록 |
| 현재 성적 계산 | `AccountService.returnPercents(liveAccounts)` | 평가할 계좌 목록 → `{계좌 ID: 수익률}` |
| 항목 구성 | `new CompetitionListResponse.CompetitionItem(...)` | 대결 정보·계산값·확정 결과 → 대결 한 건 DTO |
| 전체 응답 구성 | `new CompetitionListResponse(ongoingCount, items)` | 진행 중 개수·항목 목록 → 전체 응답 DTO |

### 계산 예시

계좌 평가의 세부 호출은 필요할 때 다음 순서로 따라간다.

```text
AccountService.returnPercents(accounts)
    ↓ snapshot() [private]
HoldingRepository.findAllByAccountIds() → 보유 종목·수량
    ↓ evaluate() [private]
KiwoomMarketClient.currentPrice() → 종목별 현재가
    ↓ AccountListResponse.from()
계좌 ID별 누적 수익률 반환
```

```text
초기 자금: 10,000,000원
현금:       9,000,000원
주식 평가액: 10주 × 현재가 120,000원 = 1,200,000원

총자산 = 9,000,000 + 1,200,000 = 10,200,000원
수익률 = (10,200,000 - 10,000,000) / 10,000,000 × 100 = 2.0%

오늘 10월 16일, 종료일 10월 26일 → d_day = 10
```

`d_day`는 조회 시 계산하며 DB에 저장하지 않는다. 완료·취소는 null, 나머지는 최소 0이다.
진행 중 개수는 `IN_PROGRESS`만 센다. 최신 생성 순으로 전체 반환하며 FE는 같은 배열로 상단 최대 3개 카드와 하단 필터 목록을 구성한다.
현재가 요청은 계좌 서비스의 DB 읽기 트랜잭션 밖에서 실행한다.

| 상태 | 수익률 데이터 | 승자 |
|---|---|---|
| `READY` | `0.0` | null |
| 거래 종료 시각 전 `IN_PROGRESS` | 사용자 계좌 현재 평가액으로 계산 | null |
| 종료 후 정산 대기 | null | null |
| `COMPLETED` | 사용자 참가자의 `finalReturnPercent` | 대결의 `winner` |
| `CANCELLED` | null | 정상 취소 데이터에서는 null |

121 브랜치에서는 종료 정산이 없어 승자가 null이었다. 완료 대결의 확정 수익률·승자 응답은 123 브랜치에서 연결됐다.
목록은 AI 수익률과 보유 종목 수를 반환하지 않는다.

### 예외·빈 결과 처리

```text
대결 없음 → {"ongoing_competition_count":0,"competitions":[]}
시세 조회 실패 → 기존 계좌 평가의 오류 응답
```

코드: [컨트롤러](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionController.java) · [대결 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java) · [계좌 서비스](../src/main/java/com/stock_spoon/river_be/account/service/AccountService.java)

## 4. 종료 정산

### 호출 흐름

```text
Spring 예약 실행: 기본 1시간 간격
    ↓
CompetitionCompletionScheduler.completeExpiredCompetitions()
    ├─ CompetitionRepository → 종료 후보 ID 조회
    └─ 각 대결의 CompetitionCompletionService.completeCompetition()
           ├─ 대결·계좌 잠금, 자산 조회
           ├─ KiwoomMarketClient.closingPrice() → 종료 거래일 종가
           ├─ 최종 자산·수익률 기록, 승자 결정
           └─ 대기 주문 취소 + Competition.complete()
    ↓ 대결별 트랜잭션 커밋
최종 결과 + COMPLETED 저장
```

### 단계별 전달값

| 단계 | 실제 메서드 | 입력 → 결과 |
|---|---|---|
| 대상 조회 | `CompetitionRepository.findCompletionCandidates(today.plusDays(2))` | 종료일 조회 상한 → 진행 중 대결 ID 목록 |
| 정산 진입 | `CompetitionCompletionService.completeCompetition(competitionId)` | 대결 ID → DB 결과 저장, 반환값 없음 |
| 대결 조회 | `CompetitionRepository.findLockedById(competitionId)` | 대결 ID → 잠근 대결 |
| 종료 판단 | `Competition.tradingEndDate()` / `tradingEndsAt()` | 대결 종료일 → 마지막 거래일·15:30 시각 |
| 참가자 조회 | `CompetitionParticipantRepository.findAllByCompetitionIdOrderByIdAsc(competitionId)` | 대결 ID → 사용자·AI 참가자와 계좌 |
| 계좌 잠금 | `AccountRepository.findLockedById(accountId)` / `EntityManager.refresh(account)` | 계좌 ID → 잠금 후 최신 잔액 |
| 보유 조회 | `HoldingRepository.findAllByAccountIds(accountIds)` | 두 계좌 ID → 보유 주식·수량 |
| 종가 조회 | `KiwoomMarketClient.closingPrice(stockCode, date)` | 종목코드·거래일 → 해당일 종가 |
| 성적 기록 | `CompetitionParticipant.recordFinalAssets(assets)` | 최종 자산 → 최종 자산·누적 수익률 변경 |
| 주문 취소 | `OrderRepository.findAllByAccountIdsAndStatus(ids, PENDING)` / `Order.cancel(now)` | 계좌 ID → 대기 주문 취소 |
| 완료 처리 | `Competition.complete(winner, completedAt)` | 승자·실제 처리 시각 → 상태·승자·완료 시각 변경 |

컨트롤러와 HTTP 응답은 없다. 다음 목록 GET 요청이 저장된 최종 결과를 읽는다.

### 계산 예시와 저장 기준

```text
요청 종료일: 일요일 10월 18일
실제 거래 종료: 금요일 10월 16일 15:30

사용자: 초기 자금 1,000만 원 → 최종 자산 1,020만 원 → +2.0%
AI:     초기 자금 1,000만 원 → 최종 자산 1,010만 원 → +1.0%

저장 결과: winner = USER, state = COMPLETED
```

- 주말은 직전 금요일 마감으로 평가하며 요청 종료일은 바꾸지 않는다. 공휴일은 아직 반영하지 않는다.
- 금요일에도 일요일 종료 대결을 찾으려고 후보 조회 상한을 `오늘 + 2일`로 잡는다. 후보별 실제 종료 시각을 다시 검사한다.
- 계좌는 ID 순서로 잠근다. 기다리는 동안 선행 체결이 커밋했을 수 있어 잔액을 새로 읽는다.
- 키움 일봉 `ka10081`에서 종료 거래일 원주가 종가를 읽는다. 보유 주식은 평가만 하며 강제 매도하거나 현금을 바꾸지 않는다.
- 승자는 반올림한 표시 수익률 대신 최종 자산/초기 자금 비율로 비교한다. 동일하면 `DRAW`다.
- 최종 자산·수익률은 참가자에, 승자·실제 완료 처리 시각은 대결에 저장한다.
- Entity 변경과 대기 주문 취소는 같은 트랜잭션에서 커밋한다. 관리 상태 Entity에는 별도 `save()`가 필요 없다.

수집기 경유 종가 요청의 세부 경로:

```text
KiwoomMarketClient.closingPrice()
  → RemoteKiwoomRestTransport.query()
  → 수집기 CollectorQueryController.query()
  → DirectKiwoomRestTransport.query()
  → 키움 일봉 조회
```

### 예외·재시도 처리

```text
아직 종료 전 / 이미 완료 → 해당 대결 처리 생략
종가 미제공 / 조회·저장 실패 → 해당 대결 트랜잭션 롤백
                         → 스케줄러 로그 기록 → 다음 주기 재시도
다른 대결 → 계속 처리
```

서버 재시작 후 과거 미처리 대결도 조회한다. 완료 대결은 재정산하지 않으며 다른 날짜 가격이나 현재가로 종가를 대체하지 않는다.

코드: [종료 스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionScheduler.java) · [종료 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionService.java) · [시세 클라이언트](../src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java)

## 5. 주문 차단

### 호출 흐름

```text
기존 주문 요청·체결 처리
    ↓ OrderService / OrderExecutionService의 대결 검사
AccountRepository.hasNonTradingCompetition(accountId, now)
    ↓ findCompetitionByAccountId()
Competition.isTradingAt(now)
    ↓
기간·상태를 만족하면 계속 처리 / 아니면 주문·체결 중단
```

### 단계별 전달값

| 진입 위치 | 호출 메서드 | 입력 → 결과 |
|---|---|---|
| 주문 사전 검사·예약 | `OrderService.assertOrderableAccount()` / `lockedAccount()` | 계좌 ID → 대결 운용 가능 여부 검사 |
| 시장가·지정가 체결 | `OrderExecutionService.executeMarket()` / `executeLimit()` | 주문·계좌 → 체결 전 대결 검사 |
| 연결 조회 | `AccountRepository.findCompetitionByAccountId(accountId)` | 계좌 ID → 연결된 대결 또는 없음 |
| 상태·시간 판단 | `Competition.isTradingAt(now)` | 현재 시각 → 진행 중이고 거래 허용 기간인지 |

### 처리 예시

```text
15:30 거래 종료
15:35 주문 요청: 아직 IN_PROGRESS여도 차단
16:00 스케줄러 정산: COMPLETED 저장
```

### 차단 결과

주문 예약·시장가 체결은 예외로 중단하고 지정가 체결은 `false`를 반환한다.
대결이 연결되지 않은 일반 계좌는 이 대결 검사에서 차단하지 않는다. 기존 계좌 권한·장시간 등 다른 주문 검사는 별도로 적용된다.

코드: [주문 서비스](../src/main/java/com/stock_spoon/river_be/order/service/OrderService.java) · [체결 서비스](../src/main/java/com/stock_spoon/river_be/execution/service/OrderExecutionService.java)

## 설정과 DB 적용

| 설정 키 | 기본값 | 역할 |
|---|---|---|
| `competition.start.enabled` | `true` | 자동 시작 활성화 |
| `competition.start.interval-ms` | `10000` | 이전 실행 종료 후 10초 대기 |
| `competition.completion.enabled` | `true` | 자동 종료 정산 활성화 |
| `competition.completion.interval-ms` | `3600000` | 이전 실행 종료 후 1시간 대기 |

- 신규 DB: 기존 `users`, `accounts` 테이블을 준비한 뒤 [competition.sql](../db/competition.sql)을 적용한다.
- 기존 대결 테이블이 있는 DB: [competition-completion.sql](../db/competition-completion.sql)로 정산 컬럼만 추가한다. 두 SQL을 중복 적용하지 않는다.
- SQL 파일은 실제 DB에 자동 실행하지 않았다. 운영 스키마 확인 후 적용해야 한다.

## 기존 기능 연결과 후속 작업

- 일반 계좌 목록에서는 대결 AI 계좌를 숨기고 사용자 대결 계좌에는 `is_duel_account=true`를 반환한다.
- AI 서버 계좌 조회는 기간 내 진행 중 대결의 AI 계좌를 포함한다. 마감 직후 상태 변경 전에는 목록에 남을 수 있지만 주문은 종료 시각으로 차단한다.
- 일반 계좌 생성·이름 변경은 대결 생성과 같은 사용자 잠금을 사용해 이름 중복 경쟁을 막는다.
- 기존 v1 일반 AI 계좌와 저장 데이터는 유지한다. v2 일반 계좌의 사용자 운용 전환·사용자 직접 주문 지원은 별도 작업이다.
- 일별 누적 수익률 기록·상세 조회, 결과 상세 API, AI 리포트 조회는 아직 구현 전이다.
- 새 기능도 **짧은 호출 흐름 → 전달값 표 → 계산·정책 예시 → 예외 처리**로 기록하고 구현된 내용과 계획을 구분한다.
