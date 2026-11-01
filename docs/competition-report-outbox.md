# 대결 종료 후 AI 리포트 요청 기록

## 현재 구현: Outbox 저장까지

```text
CompetitionCompletionScheduler.completeExpiredCompetitions() (기본 1시간 fixedDelay)
 → CompetitionRepository.findCompletionCandidates(latest)
 → 후보 ID별 CompetitionCompletionService.completeCompetition(competitionId)
   → CompetitionRepository.findLockedById(competitionId)
   → 진행 중 상태·종료 시각 검사 (미충족 또는 삭제된 대결이면 반환)
   → 참가자·계좌 잠금·종가 평가·최종 성과 및 승자 확정
   → 대기 주문 취소
   → Competition.complete(winner, now.toInstant())
   → CompetitionDailyPerformanceService.recordFinalPerformances(competitionId)
   → UUID 생성 → CompetitionReportOutbox(competition, requestId): PENDING
   → CompetitionReportOutboxRepository.saveAndFlush(outbox)
 → 트랜잭션 커밋 / 실패하면 전체 롤백
```

competition 패키지의 `CompetitionCompletionScheduler.completeExpiredCompetitions()`가 종료 후보 ID를 조회한다. 각 ID는 `CompetitionCompletionService.completeCompetition()`의 `competitionId` 인자로 전달된다. 스케줄러는 이 호출 전에 기존 일별 기록 보완 작업도 수행한다.

서비스는 대결을 잠금 조회하고, 종료 시각이 지난 진행 중 대결인지 검사한다. 해당하면 기존 정산 로직을 실행해 최종 성과와 승자를 확정하고 대기 주문을 취소한다. 이어 `recordFinalPerformances(competitionId)`로 마지막 날의 성과를 기록한다.

그다음 UUID를 `requestId`로 생성하고 해당 대결과 함께 Outbox 엔티티를 만든다. 생성자는 상태를 `PENDING`으로 설정한다. `saveAndFlush()`는 DB에 변경을 반영하지만 커밋은 서비스 메서드가 정상 반환할 때 이루어진다. 최종 성과·완료 상태·대기 주문 취소·Outbox는 같은 트랜잭션이므로 하나가 실패하면 모두 롤백된다. 스케줄러가 앞서 별도 트랜잭션으로 저장한 과거 일별 기록은 이 롤백 범위에 포함되지 않는다.

이미 완료된 대결은 상태 검사에서 반환하므로 반복 호출해도 새 요청을 만들지 않는다. 대결 잠금과 Outbox의 competition_id 유일 제약으로 최초 요청 중복 저장을 막는다. 기존 완료 대결의 요청을 소급 생성하는 기능은 아직 없다.

## 저장값과 로그

| 값 | 의미 |
|---|---|
| competition_id | 리포트를 요청할 대결 |
| request_id | 재전송 시에도 유지할 요청 식별자 |
| status=PENDING | SQS 전송 대기 |
| status=SENT | SQS 전송 성공 상태. AI 생성 완료를 의미하지 않음 |
| created_at | Outbox 생성 시각 |

Outbox 저장 단계에서 실패하면 `event=competition_report_outbox_creation_failed`, `stage=outbox_save`, `competitionId`, `requestId`, `causeType` 및 스택 트레이스를 기록하고 예외를 다시 던진다. `saveAndFlush()`는 앞선 정산 변경도 반영하므로 스택 트레이스의 실제 DB 원인도 확인해야 한다.

스케줄러에서는 커밋 단계 실패까지 `event=competition_completion_failed`, `stage=completion_transaction`과 대결 ID·스택 트레이스로 확인할 수 있다. 실패한 대결은 진행 중으로 남아 다음 주기에 다시 후보가 된다. 다른 후보 처리는 계속한다.

회원 탈퇴 시 `UserDeletionRepository.deleteAllForUser(userId)`가 대상 회원 대결의 Outbox를 대결보다 먼저 삭제한다. 다른 회원의 기록은 삭제하지 않는다.

## 전송 준비: 대기 조회와 상태 변경

```text
현재 테스트에서 검증한 호출 (자동 전송 서비스는 아직 미구현)
 → CompetitionReportOutboxRepository.findByStatusOrderByIdAsc(PENDING, PageRequest.of(0, 처리개수))
 → PENDING만 outbox_id 오름차순으로 제한 조회
 → [미구현: SQS 전송 성공 확인]
 → CompetitionReportOutbox.markSent()
 → 호출한 서비스의 트랜잭션 커밋 시 SENT 반영
```

`findByStatusOrderByIdAsc()`의 `status`에는 조회할 상태, `pageable`에는 한 번에 가져올 개수를 전달한다. 오래된 ID부터 조회하고, 이미 SENT인 행은 제외한다. List로 반환하므로 전체 개수를 세는 COUNT 쿼리는 필요하지 않다. `status, outbox_id` 인덱스도 엔티티와 DDL에 반영했다.

`markSent()`는 영속 상태의 엔티티를 SENT로 바꾸는 메서드다. request_id는 유지한다. 메서드 자체가 SQS에 전송하거나 트랜잭션을 만들지는 않는다. 현재 운영 경로에서는 아직 호출하지 않으며, 자동 전송 서비스가 성공 응답을 확인한 뒤 호출하도록 연결할 예정이다. 대기 조회 메서드는 작업을 독점하는 잠금 기능이 없으므로, 여러 인스턴스가 함께 보내는 상황의 잠금·재확인은 전송 서비스 구현 시 다룬다.

## 다음 단계 / 미구현

- PENDING 조회와 SENT 변경 메서드는 구현했다. SQS 전송, 실제 상태 변경 호출 및 재시도 연결은 아직 구현하지 않았다.
- AI 요청의 participant_id 의미, portfolio_reason_ids의 데이터 출처, 요청 식별자 메시지 필드 합의가 필요하다.
- `REPORT_QUEUE_URL`, ap-northeast-2, 기본 자격 증명 체인 및 EC2 IAM Role을 사용할 예정이다.
- SQL 파일은 자동 실행되지 않는다. 운영 DB에 이번 테이블을 적용하지 않았다.

## 관련 코드

- [완료 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionService.java)
- [스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionScheduler.java)
- [Outbox 엔티티](../src/main/java/com/stock_spoon/river_be/competition/entity/CompetitionReportOutbox.java)
- [Outbox Repository](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionReportOutboxRepository.java)
- [회원 탈퇴 삭제](../src/main/java/com/stock_spoon/river_be/user/repository/UserDeletionRepository.java)
- [DDL](../db/competition-report-outbox.sql)
