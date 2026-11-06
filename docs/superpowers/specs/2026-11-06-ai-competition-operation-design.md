# AI 간 대결 운용 확장: 코드 확인 결과와 설계안

## 확정 범위

BE만 수정한다. 생성 API와 전략 저장 계약은 유지한다. 새 SQS 전략 전송, FE·AI 수정, 자동 시작 활성화, 운영 DB 적용, 푸시·PR 변경은 제외한다. 사용자 요청에 따라 계좌별 매매, 성과 기록, 정산, 조회를 단계별 구현·검증한다.

## 1단계: 현재 코드 확인 (완료)

기준 브랜치: `feat/251-competition/detail-response`, HEAD `28bec4e`.

- `OrderService.assertOrderableAccount`: 소유자·활성 계좌·주문 출처·대결 거래 가능 상태를 검사한다. 기존 주문 메시지는 `user_id`, `account_id`를 사용한다. 새로운 `participant_id` 필드를 주문 메시지에 임의로 필수화하지 않는다.
- `OrderRequestTransactions`: 계좌별 잠금·예약·중복 이벤트 검사 후 지정한 계좌로 체결한다. 다수 참가자를 위한 별도 주문 엔진을 만들 필요는 없다. 계좌 간 격리 통합 테스트가 필요하다.
- `Competition.start`, `CompetitionRepository.findWaitingToStart`: AI 간 대결의 시작을 차단한다. 운영 자동 시작 제한은 이번에도 유지한다.
- `CompetitionDailyPerformanceService.recordMissingDays`: 참가자 2명·AI 1명 검사 때문에 다수 AI 기록이 실패한다. 이후 계산은 이미 참가자·계좌별 Map을 사용한다.
- `CompetitionCompletionService`: 참가자 2명·AI 1명 검사 및 사용자/AI 승자 비교가 있다. N명 평가·최종 기록 처리로 확장해야 한다.
- `CompetitionReportDispatchService`: AI 참가자 1명만 지원한다. AI 대결 정산이 이 기존 Outbox를 만들면 재시도 오류가 반복된다. 별도 리포트 계약 확정 전까지 AI 간 대결에는 기존 리포트 Outbox를 생성하지 않는 안을 제안한다.
- `CompetitionDetailService`: 이미 AI 2~4명, 전략, 매매 횟수, 최고 수익률 보유 종목을 지원한다. 참가자별 자산·수익률·그래프는 아직 응답하지 않는다.
- `CompetitionService.getCompetitionResult`: 사용자 1명·AI 1명을 전제로 결과와 그래프를 구성한다.
- `CompetitionCancellationService`: AI 간 대결 취소를 명시적으로 거절한다. 모든 참가자의 주문 취소·계좌 비활성화로 확장할 대상이다.

## 구현 방식 제안

기존 계좌·주문·참가자·일별 성과 테이블을 재사용한다. 대결별 별도 주문 엔진이나 참가자별 별도 API는 만들지 않는다. 결과 응답에 필요한 순위·공동 우승 ID는 확정 자산과 초기금으로 계산한다. 현재 요구만으로 새 DB 컬럼을 추가하지 않는 방식을 우선한다. 실제 수정에서 필요해지면 운영 스키마 변경은 별도 안내한다.

### 2단계: 참가자 검증과 계좌 격리

대결 유형별 유효 구성(user_vs_ai=사용자 1+AI 1, ai_vs_ai=AI 2~4 및 선언 인원 일치)을 일관되게 검사한다. 계좌 소유자, 계좌 중복, 전략과 참가자의 연결도 검증한다. 기존 주문 수신 계약을 유지하고 다른 AI 계좌의 현금·보유량·예약이 바뀌지 않음을 테스트한다. READY 상태의 주문 거절도 유지한다.

### 3단계: 일별 성과와 최종 정산

참가자 N명 각각에 기존 계산식을 적용한다. 최종 평가는 종료일 종가, 현금, 보유량으로 확정한다. 계좌 잠금 순서는 ID 오름차순이다. 어느 한 참가자 평가가 실패해도 대결 전체 정산을 롤백한다. 반복 실행은 최종 결과를 중복 저장하지 않는다. 기존 사용자 vs AI 리포트 Outbox 동작은 보존한다.

공동 순위는 반올림 이전의 자산/초기금 비율로 비교한다. 동률 공동 1위와 다음 순위는 1,1,3 방식으로 제안한다. 다수 AI 대결에서 기존 winner enum은 단독 1위 AI이면 AI, 공동 1위이면 DRAW로 저장하되 참가자 식별은 응답의 winner_participant_ids를 사용한다.

### 4단계: 상세·결과·취소 API

기존 v2 경로와 사용자 vs AI의 기존 응답 의미를 유지하는 추가 방식으로 제안한다.

- `GET /api/v2/users/me/competitions/{competition_id}`: participants 항목에 total_assets, cumulative_return_percent, daily_performances 추가. 참가자별 그래프 날짜·수익률을 제공한다. 기존 최상위 사용자 그래프는 그대로 유지하며 AI 대결은 대표 AI를 임의 선택하지 않는다. 현재 자산은 조회 시 평가액이며 최종 결과와 구분한다.
- `GET /api/v2/users/me/competitions/{competition_id}/result`: winner_participant_ids 추가. participants에 final_asset_value, final_return_percent, rank, daily_performances 추가. AI 대결에는 기존 user_final_return_percent, ai_final_return_percent를 null로, 최상위 1:1 그래프를 빈 배열로 반환하는 안이다. 미완료는 기존 409 유지.
- `POST /api/v2/users/me/competitions/{competition_id}/cancel`: AI 2~4명의 모든 계좌·대기 주문 정리. 기존 멱등 취소·소유권 정책 유지.
- 대결 목록: 현재 단일 수익률은 사용자 성적이다. AI 대결에는 임의의 대표 AI 성적을 넣지 않는다. 참가자별 비교는 상세·결과를 사용한다.

계좌 상세·보유 종목·매매 내역은 기존 API를 재사용하며 AI 계좌 접근 권한을 불필요하게 확대하지 않는다. AI 주문에 competition_id/participant_id를 추가하는 계약 변경이 필요하다면 별도 합의한다.

### 5단계: 검증 및 마무리

2·3·4명, 동일 종목을 서로 다른 계좌에서 매매, 잔액 부족·중복 수신·비활성 계좌·READY 차단, 기록 복구·종가 장애 롤백, 공동 우승·반올림 경계, 소유권·취소 재요청 및 기존 user_vs_ai 회귀 테스트를 수행한다. 테스트용 진행 상태 fixture를 사용하고 운영 시작 경로를 열지 않는다. 전체 테스트 및 SpotBugs 확인 후 단계별 로컬 커밋까지만 한다.

## 확정이 필요한 API 설계

사용자가 앞서 진행을 승인한 작업 범위는 유지한다. 이번 코드 확인으로 구체화한 참가자별 성적 응답과 공동 순위, 기존 리포트 발행 제외 방식은 위 설계안이며 아직 제품 코드에 적용하지 않았다. 해당 응답과 동작을 확인받은 뒤 구현 계획과 실패 테스트를 작성한다.
