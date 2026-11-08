# AI 참가자별 대결 리포트 저장·조회

사용자 vs AI는 보고서 1개, AI 간 대결은 AI 참가자 수만큼 저장·조회한다. 보고서 수신 여부는 대결 상태와 별개다. 이 문서는 현재 구현을 설명하며 이전 단일 보고서 계획보다 우선한다.

## 저장 흐름

```text
POST /api/v1/competitions/{competition_id}/report
 → CompetitionReportSaveController.save(jwt, competitionId, request)
   → @Valid: 양수 participant_id, news/thoughts 배열·항목 검증
 → CompetitionReportService.save(Long.parseLong(jwt.getSubject()), competitionId, request)
   → competitions.findLockedById(competitionId): 대결 잠금·소유권 확인
   → participants.findById(request.participantId)
     → 해당 대결 소속·AI 운용·계좌 소유자 검사
   → analyses.findAllByCompetitionIdAndParticipantIdOrderByDateDescIdDesc(...)
   → summaries.findAllByCompetitionIdAndParticipantIdOrderByIdAsc(...)
   → 해당 AI 본문만 deleteAll()
   → news → CompetitionMarketAnalysis(participant, null, title, summary) 저장
   → thoughts → CompetitionJudgmentSummary(participant, title, text) 저장
   → participant.markReportReady()
 → 같은 트랜잭션 커밋 / 오류 시 기존 내용 교체·완료 표시 전체 롤백
 → 200 {competition_id, message: "success"}
```

AI 서버의 요청은 competition.controller 패키지의 `CompetitionReportSaveController.save()`로 들어온다. 토큰은 `jwt`, URL의 대결 ID는 `competitionId`, JSON은 `request` 인자로 받는다. 컨트롤러는 JWT subject의 소유자 ID를 long으로 바꿔 서비스의 `userId`에 넘긴다. 기존 AI 전용 인증과 CSRF 정책은 유지한다. SQS 본문에서 user_id를 제거한 것과 별개로 저장 인증 토큰 계약은 유지한다.

`CompetitionReportService.save()`는 대결을 잠금 조회해 동시 저장을 순서대로 처리한다. `request.participantId`로 참가자를 찾고 소속·AI 여부·계좌 소유자를 확인한다. 해당 참가자의 기존 본문만 삭제하고 새 본문을 저장하므로 다른 AI의 보고서를 덮어쓰지 않는다. 본문 Entity 생성자는 참가자의 대결을 competition 관계에 함께 설정한다. 뉴스 날짜는 null이며 마지막에 해당 참가자의 report_ready만 true로 만든다. news/thoughts가 모두 빈 배열이어도 정상 수신했으므로 completed다.

```json
{
  "participant_id": 456,
  "news": [{"title": "시장 분석", "summary": "분석 본문"}],
  "thoughts": [{"title": "투자 판단", "text": "판단 본문"}]
}
```

participant_id는 SQS 요청의 AI 참가자 ID다. 계좌 ID·에이전트 ID와 다르며 사용자 vs AI도 생략하지 않는다. AI 서버의 콜백 수정이 필요하다. 성공 응답은 기존 competition_id/message를 유지한다.

## 조회 흐름

```text
GET /api/v2/users/me/competitions/{competition_id}/report
 → CompetitionReportController.get(jwt, competitionId)
 → CompetitionReportService.get(Long.parseLong(jwt.getSubject()), competitionId)
   → competitions.findById(competitionId): 본인 소유 검사
   → participants.findAllByCompetitionIdOrderByIdAsc(competitionId): 계좌 함께 조회
   → CompetitionParticipants.valid(competition, links)
   → AI 간 대결이면 agents.findAllByParticipantCompetitionIdOrderByPositionAsc(...)
     → validStrategies() 확인 → position 순서로 참가자 정렬
   → analyses.findAllByCompetitionIdOrderByDateDescIdDesc(competitionId)
   → summaries.findAllByCompetitionIdOrderByIdAsc(competitionId)
   → 본문을 participant_id별로 묶기
   → AI 참가자마다 ReportItem 구성
     → 미수신: pending, [], []
     → 정상 수신: completed, 해당 AI 본문 (빈 배열 가능)
 → CompetitionReportResponse(대결 정보, reports) → 200 JSON
```

사용자의 조회 요청은 `CompetitionReportController.get()`으로 들어온다. URL의 대결 ID와 토큰에서 꺼낸 사용자 ID를 서비스로 전달한다. 소유권 확인 후 전체 참가자를 읽고 AI 계좌만 응답에 포함한다. 사용자 vs AI의 표시 이름은 AI, AI 간 대결은 기존 결과 화면과 같은 에이전트 position 기준 AI 1/AI 2다. 계좌명이나 참가자 PK 순서로 번호를 정하지 않는다.

본문은 AI마다 반복 조회하지 않고 대결당 분석·요약을 각각 한 번 읽어 참가자 ID별로 묶는다. 보고서가 없는 AI도 pending 항목을 만들어 모든 AI의 버튼을 표시할 수 있다. REPEATABLE_READ 읽기 트랜잭션으로 준비 상태와 본문을 같은 스냅샷에서 읽는다. 조회는 AI 호출·SQS 발행·DB 변경을 하지 않고 대결 상태에 따른 공개 제한도 기존처럼 추가하지 않는다.

```json
{
  "competition_id": 123,
  "competition_name": "반도체 집중",
  "start_date": "2026-11-02",
  "end_date": "2026-11-06",
  "reports": [
    {
      "participant_id": 456,
      "display_name": "AI 1",
      "report_state": "completed",
      "market_analyses": [{"analysis_id": 101, "date": null, "title": "시장 분석", "content": "본문"}],
      "judgment_summaries": [{"title": "투자 판단", "content": "본문"}]
    },
    {
      "participant_id": 457,
      "display_name": "AI 2",
      "report_state": "pending",
      "market_analyses": [],
      "judgment_summaries": []
    }
  ]
}
```

기존 최상위 report_state/market_analyses/judgment_summaries를 reports 항목 안으로 옮겼다. 분석·요약 내부 필드와 대결 정보·URI는 유지한다. FE는 participant_id로 선택한 보고서를 표시한다.

## DB와 삭제 순서

| 위치 | 변경 | 목적 |
|---|---|---|
| competition_participants | report_ready BOOLEAN NOT NULL DEFAULT FALSE | 참가자별 정상 수신 여부 |
| competition_market_analyses | participant_id NOT NULL FK | 분석의 대상 AI |
| competition_judgment_summaries | participant_id NOT NULL FK | 요약의 대상 AI |
| competitions.report_ready | 컬럼 보존 | 구버전 이관에만 사용. 새 저장·조회에서 판단·갱신하지 않음 |

`UserDeletionRepository.deleteAllForUser()`는 Outbox와 본문을 참가자보다 먼저 삭제한다. 새 FK 때문에 필요한 순서다. 계좌 비활성화만으로 보고서를 삭제하지 않는다.

신규 설치는 [리포트 DDL](../db/competition-reports.sql), 기존 DB는 [참가자 이관 SQL](../db/competition-reports-participant.sql)을 선택한다. 단일 AI의 기존 본문 PK·내용·날짜와 빈 보고서의 완료 표시를 보존한다. 대상 AI가 불명확하면 임의 배분하지 않는다. 중지·재개와 계약 변경은 [배포 안내](competition-report-deployment.md)를 따른다. SQL은 자동 실행되지 않는다.

## 예외·검증

- HTTP participant_id 누락/null/0/음수 또는 잘못된 본문: @Valid에서 400.
- 대결 없음·타인 대결: 404 COMPETITION_NOT_FOUND.
- 타 대결 참가자·사용자 참가자·없는 참가자: 404 AI_PARTICIPANT_NOT_FOUND.
- 서비스 직접 호출의 잘못된 참가자 ID: 400 INVALID_PARTICIPANT_ID.
- 조회 시 참가자 구성·표시 순서 불명확: 503 COMPETITION_REPORT_UNAVAILABLE.
- 저장 실패 시 기존 보고서와 준비 상태를 롤백한다. 같은 대결의 서로 다른 AI 저장도 대결 잠금으로 직렬화한다.
- 로컬 H2·실제 HTTP/인증으로 단일/다중 AI, pending/completed, 재전송·동시 저장·롤백·참가자 검증·회원 탈퇴를 검사한다. 실제 AI 서버·AWS·운영 MySQL 이관은 별도 확인 대상이다.

## 관련 코드

- [저장 Controller](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionReportSaveController.java)
- [조회 Controller](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionReportController.java)
- [Service](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionReportService.java)
- [저장 DTO](../src/main/java/com/stock_spoon/river_be/competition/dto/CompetitionReportSaveRequest.java)
- [조회 DTO](../src/main/java/com/stock_spoon/river_be/competition/dto/CompetitionReportResponse.java)
- [참가자 Entity](../src/main/java/com/stock_spoon/river_be/competition/entity/CompetitionParticipant.java)
- [테스트](../src/test/java/com/stock_spoon/river_be/competition/CompetitionReportTests.java)
