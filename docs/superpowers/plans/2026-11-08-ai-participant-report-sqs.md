# AI 참가자별 리포트 SQS 요청 구현 계획

**목표:** 대결 종료 시 AI 참가자마다 Outbox와 독립된 SQS 요청을 생성한다.
**설계:** 기존 종료 트랜잭션·전송 스케줄러를 유지한다. Outbox에 대상 참가자를 연결하고 `(competition_id, participant_id)`로 중복을 막는다. SQS 본문에서 `user_id`를 제거한다.
**범위:** 이슈 #262의 요청 전송 부분. 참가자별 보고서 저장·조회, AI 자동 시작 정책은 후속 작업이다. 운영 DB는 적용하지 않는다. 검증 후 사용자 요청에 따라 현재 브랜치에 커밋·푸시한다.

1. 테스트: 다중 AI 종료 시 4건 생성, 반복 종료 중복 방지, 중간 저장 실패 전체 롤백, 참가자별 reason_id 분리, 한 요청 실패 후 다른 요청 전송 및 고정 eventId 재시도를 검증한다.
2. Entity/서비스: `CompetitionReportOutbox(competition, participant, requestId)` 추가, 종료 시 AI별 반복 저장, dispatch에서 지정된 참가자의 소속·AI 여부 검증 후 해당 계좌만 조회한다.
3. DTO/호출자: `CompetitionReportRequest(competitionId, participantId, portfolioReasonIds)`로 변경하고 모든 호출자·기존 테스트를 수정한다. 참가자 FK에 맞춰 회원 탈퇴 삭제 순서를 보완한다.
4. DB/문서: 신규 설치 DDL 및 기존 Outbox 이관 SQL을 구분한다. 단일 AI인 기존 행만 참가자를 역산하고 모호한 행은 수동 확인한다. request_id·전송 상태는 보존한다. 호출 흐름과 배포 제약을 갱신한다.
5. 검증: 변경된 요청 전송·완료·회원 탈퇴 테스트와 대결 관련 회귀 테스트를 실행하고 결과를 기록한다. 실제 AWS 전송 및 운영 MySQL 이관은 수행하지 않는다.

주의: 기존 저장 API는 대결당 리포트 하나를 저장한다. AI 다중 리포트 콜백을 운영에서 활성화하기 전에 저장·조회 확장을 완료해야 덮어쓰기를 막을 수 있다.

## 구현·검증 결과

- 위 요청 전송 범위를 구현했다. 전송 스케줄러·Publisher·reason_id 조회 쿼리는 기존 구조를 유지한다.
- 변경 전 다중 AI 요청 수와 user_id 제거 테스트 실패를 확인한 뒤 변경을 적용했다.
- `gradlew.bat test --offline --tests 'com.stock_spoon.river_be.competition.*' --tests '*UserWithdrawalTests'`: 241개 중 236개 통과, 5개 건너뜀, 실패·오류 0건.
- `git diff --check` 통과. 운영 MySQL 이관·실제 SQS/AI 연동은 수행하지 않았다.

## 작업 요약과 호출 흐름

```text
CompetitionCompletionScheduler.completeExpiredCompetitions()
 → CompetitionCompletionService.completeCompetition(competitionId)
   → 정산·완료·최종 성과 저장
   → links의 AI 참가자마다 CompetitionReportOutbox(competition, participant, requestId)
   → reportOutboxes.saveAndFlush(outbox): 모든 요청과 완료를 함께 커밋

CompetitionReportDispatchScheduler.dispatchPendingReports()
 → PENDING Outbox ID를 순서대로 조회
 → CompetitionReportDispatchService.dispatch(outboxId): 요청별 트랜잭션
   → outbox.getParticipant(): 대상 AI의 계좌 확인
   → findExecutedAiReasonIds(accountId): 체결된 AI 주문의 reason_id만 수집
   → CompetitionReportRequest(competitionId, participantId, reasonIds)
   → CompetitionReportRequestPublisher.publish(requestId, payload)
   → 전송 성공 시 해당 Outbox만 SENT, 실패 시 PENDING으로 롤백
```

종료 서비스는 모든 AI의 요청을 먼저 DB에 기록한다. SQS 네트워크 호출은 별도 전송 스케줄러에서 수행한다. AI마다 서로 다른 requestId를 사용하며 재시도 시에는 기존 ID를 유지한다. 메시지에서 user_id를 제거하고 대결 ID·AI 참가자 ID·판단 근거 UUID 목록만 보낸다. 한 참가자의 전송 실패가 다른 참가자의 전송을 막지 않는다.

Outbox에 participant_id FK와 대결/참가자 복합 UNIQUE를 추가했다. 기존 테이블 이관 SQL은 단일 AI가 확인되는 기존 요청의 참가자를 연결하고 요청 ID와 상태를 보존한다. 회원 탈퇴는 Outbox를 참가자보다 먼저 삭제한다. 상세 절차는 competition-report-deployment.md에 정리했다.
