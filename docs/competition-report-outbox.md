# 대결 종료 후 AI 리포트 요청 기록

사용자 vs AI와 AI 간 대결 모두 **AI 참가자당 요청 1건**을 생성한다. 참가자별 저장·조회도 확장했으며 [저장·조회 문서](competition-participant-reports.md)를 따른다. AI 대결은 [준비 완료·시작 계약](competition-ai-start.md)을 충족한 뒤 장중에 시작한다. 리포트 실제 연동은 AI 서버의 participant_id 추가·FE의 reports 배열 대응 및 운영 DB 이관 후 활성화한다.

## 대결 종료와 Outbox 저장

```text
CompetitionCompletionScheduler.completeExpiredCompetitions() (평일 15:30·15:35 KST + 1시간 fixedDelay, 정산 전용 스레드)
 → CompetitionRepository.findCompletionCandidates(latest)
 → 후보 ID별 CompetitionCompletionService.completeCompetition(competitionId)
   → CompetitionRepository.findLockedById(competitionId)
   → 진행 중 상태·종료 시각 검사 (미충족 또는 삭제된 대결이면 반환)
   → 참가자·계좌 잠금·종가 평가·최종 성과 및 승자 확정
   → 대기 주문 취소
   → Competition.complete(winner, now.toInstant())
   → CompetitionDailyPerformanceService.recordFinalPerformances(competitionId)
   → 참가자 순회: account.isAiManaged()인 참가자만 선택
     → AI마다 UUID 생성 → CompetitionReportOutbox(competition, participant, requestId): PENDING
     → CompetitionReportOutboxRepository.saveAndFlush(outbox)
 → 트랜잭션 커밋 / 실패하면 전체 롤백
```

competition 패키지의 `CompetitionCompletionScheduler.completeExpiredCompetitions()`가 종료 후보 ID를 조회한다. 각 ID는 `CompetitionCompletionService.completeCompetition()`의 `competitionId` 인자로 전달된다. 종료 정산을 먼저 수행한 뒤 일별 기록 보완 작업을 실행한다.

competition.config.CompetitionSchedulingConfiguration은 competitionCompletionTaskScheduler라는 전용 스케줄러 빈(스레드 1개)을 만든다. completeExpiredCompetitions()의 @Scheduled.scheduler가 이 빈을 지정하므로 정산·일별 성과 복구가 오래 걸려도 공용 taskScheduler의 대결 시작·주문 구독 갱신·주문 요청 복구를 같은 실행 스레드에서 막지 않는다. DB 연결 풀 등 다른 공유 자원까지 분리한 것은 아니다. competition.completion.enabled=false이면 작업과 전용 빈을 모두 생성하지 않는다. 기본 1시간 fixedDelay와 업무 로직은 유지한다.

서비스는 대결을 잠금 조회하고, 종료 시각이 지난 진행 중 대결인지 검사한다. 해당하면 기존 정산 로직을 실행해 최종 성과와 승자를 확정하고 대기 주문을 취소한다. 이어 `recordFinalPerformances(competitionId)`로 마지막 날의 성과를 기록한다.

그다음 `links`의 참가자를 순서대로 확인하고 계좌가 AI 운용인 참가자마다 UUID를 `requestId`로 생성한다. 대결은 `competition`, 대상 AI 참가자는 `participant`, UUID는 `requestId` 인자로 Outbox 생성자에 전달한다. 생성자는 대결 소속·AI 계좌 여부를 확인하고 상태를 `PENDING`으로 설정한다. 사용자 vs AI는 1건, AI가 4명이면 4건을 만든다. `saveAndFlush()`는 DB에 변경을 반영하지만 커밋은 서비스 메서드가 정상 반환할 때 이루어진다. 최종 성과·완료 상태·대기 주문 취소·모든 Outbox는 같은 트랜잭션이므로 두 번째 AI의 Outbox 저장이 실패해도 앞선 첫 번째 요청까지 함께 롤백된다. 스케줄러가 별도 트랜잭션으로 저장한 과거 일별 기록은 이 롤백 범위에 포함되지 않는다.

이미 완료된 대결은 상태 검사에서 반환하므로 반복 호출해도 새 요청을 만들지 않는다. 대결 잠금과 Outbox의 (competition_id, participant_id) 유일 제약으로 참가자별 최초 요청 중복 저장을 막는다. request_id도 전역 유일하다. 기존 완료 대결의 요청을 소급 생성하는 기능은 없다.

일별 기록 복구 후보는 daily_performance_complete=false로 제한한다. 완료 대결의 전체 평일 기록을 검증·복구하면 같은 트랜잭션에서 이 값을 true로 저장해 다음 후보에서 제외한다. 진행 중 대결은 계속 후보로 남긴다. 상세한 검사 범위와 운영 DB 변경은 [스케줄러 점검 문서](competition-scheduler-review.md)를 참고한다.

## 저장값과 로그

| 값 | 의미 |
|---|---|
| competition_id | 리포트를 요청할 대결 |
| participant_id | 이 요청의 대상 AI 참가자. 계좌 ID는 이 참가자의 account_id로 찾음 |
| request_id | 재전송 시에도 유지할 요청 식별자 |
| status=PENDING | SQS 전송 대기 |
| status=SENT | SQS 전송 성공 상태. AI 생성 완료를 의미하지 않음 |
| created_at | Outbox 생성 시각 |

Outbox 저장 단계에서 실패하면 `event=competition_report_outbox_creation_failed`, `stage=outbox_save`, `competitionId`, `participantId`, `requestId`, `causeType` 및 스택 트레이스를 기록하고 예외를 다시 던진다. `saveAndFlush()`는 앞선 정산 변경도 반영하므로 스택 트레이스의 실제 DB 원인도 확인해야 한다.

스케줄러에서는 커밋 단계 실패까지 `event=competition_completion_failed`, `stage=completion_transaction`과 대결 ID·스택 트레이스로 확인할 수 있다. 실패한 대결은 진행 중으로 남아 다음 주기에 다시 후보가 된다. 다른 후보 처리는 계속한다.

회원 탈퇴 시 `UserDeletionRepository.deleteAllForUser(userId)`가 대상 회원 대결의 Outbox를 참가자·대결보다 먼저 삭제한다. Outbox의 participant_id FK를 해제하기 위한 순서다. 다른 회원의 기록은 삭제하지 않는다.

## 자동 전송: 대기 조회와 상태 변경

```text
CompetitionReportDispatchScheduler.dispatchPendingReports() (기본 1분 fixedDelay, 전용 스레드)
 → CompetitionReportOutboxRepository.findPendingUpperBound(): 이번 실행의 최대 후보 ID
 → findPendingIdsBetween(afterId, upperId, PageRequest.of(0, batchSize))
 → 후보 ID별 CompetitionReportDispatchService.dispatch(outboxId)
   → findLockedById(outboxId): 행 잠금 및 PENDING 재확인
   → 완료 대결인지 검사
   → outbox.getParticipant(): 이 요청에 연결된 AI 참가자
   → 대결 소속·AI 계좌·계좌 소유자 검사 → account_id 결정
   → CompetitionReportOutboxRepository.findExecutedAiReasonIds(accountId)
   → null 제외·누락 경고·중복 제거 → CompetitionReportRequest 구성
   → CompetitionReportRequestPublisher.publish(outbox.requestId, payload)
   → 성공 응답 확인 → outbox.markSent() → outboxes.flush()
 → 요청별 트랜잭션 커밋 후 competition_report_outbox_sent 로그
```

competition.service의 CompetitionReportDispatchScheduler.dispatchPendingReports()가 호출되면 우선 PENDING의 최대 ID를 읽는다. 이후 마지막 조회 ID보다 크고 최대 ID 이하인 후보를 ID 오름차순으로 batchSize개씩 읽는다. 매 실행에서 기존 후보 전체를 순회하되 같은 실패 행을 같은 실행에서 반복하지 않으며, 실행 도중 새로 생성된 요청은 다음 주기에 처리한다. 조회 결과는 List여서 전체 개수를 세는 COUNT 쿼리가 필요하지 않다. status, outbox_id 인덱스를 사용하도록 엔티티와 DDL을 구성했다.

각 후보 ID는 별도 서비스 프록시의 `dispatch(outboxId)`에 전달한다. 서비스는 요청별 READ_COMMITTED 트랜잭션에서 Outbox에 PESSIMISTIC_WRITE 잠금을 잡고 상태를 다시 확인한다. 다른 서버가 이미 SENT로 바꿨거나 회원 탈퇴로 삭제됐다면 전송하지 않고 반환한다. `outbox.getParticipant()`로 대상 AI를 읽는다. 대결이 완료되었는지, 참가자가 해당 대결 소속인지, 계좌가 AI 운용이고 대결 소유자의 계좌인지 검사한다. 통과하면 그 참가자의 계좌 ID를 `findExecutedAiReasonIds(accountId)`로 전달한다. 다른 AI 참가자의 주문을 함께 조회하지 않는다. 요청 본문의 participant_id에는 이 참가자 ID를 담고 user_id는 보내지 않는다.

portfolio_reason_ids에는 EXECUTED 상태의 AI 주문에 저장된 reason_id만 주문 ID 순서대로 수집하고 동일 문자열의 중복을 제거한다. 취소·미체결·사용자 주문·다른 계좌 주문은 제외한다. 과거 주문에서 ID가 없거나 리포트가 없으면 임의의 ID를 생성하지 않고 제외하며 competition_report_reason_ids_missing 로그에 missingOrderCount를 기록한다. 결과 목록이 비어 있으면 빈 배열로 요청한다.

Publisher의 성공 반환 후 markSent()와 flush()를 호출한다. request_id는 변경하지 않는다. 행 잠금은 SQS 응답을 기다리는 동안에도 유지한다. 일반적인 동시 전송은 막지만 네트워크를 기다리는 동안 DB 연결과 잠금을 점유하는 비용이 있다. Publisher의 대기는 최대 12초이며, DB 자체 잠금 대기 시간은 DB 설정을 따른다.

전송 실패나 SENT 저장 실패는 해당 요청 트랜잭션만 롤백해 PENDING으로 남기고 다음 주기에 재시도한다. SQS 전송 성공 직후 DB 커밋 실패·프로세스 종료가 발생하면 이미 전달된 요청을 같은 ID로 재전송할 수 있다. 이 중복은 Standard Queue와 Outbox 조합에서도 피할 수 없으므로 AI 소비자가 eventId 기준으로 중복을 처리해야 한다. DB 커밋이 끝난 뒤 스케줄러가 competition_report_outbox_sent 로그를 남긴다.

실패 로그 competition_report_dispatch_failed의 stage는 message_build, sqs_send, sent_save, dispatch_transaction 또는 candidate_query다. 서비스 내부 실패는 stage와 outboxId·competitionId·participantId·requestId·causeType 및 스택 트레이스로 원인을 추적한다. 행 잠금 조회 실패·최종 커밋 실패는 스케줄러의 dispatch_transaction 로그에서 outboxId로 확인한다. 특정 요청의 실패는 같은 대결의 다른 AI 요청을 포함해 뒤쪽 요청 처리를 막지 않는다. 다음 실행에서는 PENDING으로 남은 요청만 같은 eventId로 재시도한다.

실제로 후보를 처리한 실행은 competition_report_dispatch_run_finished 로그로 attemptedCount·successCount·failedCount·skippedCount·elapsedMs를 남긴다. DB 잠금과 네트워크 대기로 인한 실행 시간 증가를 관찰할 수 있다. 실제 운영 성능은 측정하지 않았다.

## 운영 검증과 후속 확인

- PENDING 조회·메시지 구성·SQS 전송·SENT 저장 및 실패 재시도를 연결했다. reason_id 수신·저장 코드와 함께 로컬에서 검증했으며 운영에는 아직 적용하지 않았다.
- 고정 요청 ID는 본문을 바꾸지 않고 SQS String 메시지 속성 eventId로 전달한다. AI 소비자는 이 속성을 읽어 중복 처리를 방지해야 하며, 실제 소비 측 지원은 확인하지 않았다.
- SQL 파일은 자동 실행되지 않는다. 운영 DB에 이번 테이블을 적용하지 않았다.

## SQS 전송부

```text
CompetitionReportRequest(competitionId, participantId, portfolioReasonIds)
 → CompetitionReportRequestPublisher.publish(requestId, payload)
   → JSON 본문 직렬화 및 크기 검사
   → SendMessageRequest: 본문 3개 필드 + messageAttributes.eventId=requestId
   → 전용 SqsAsyncClient.sendMessage(request).get(12초)
   → messageId 존재 확인
 → 성공이면 messageId 반환 / 오류·타임아웃이면 예외
```

competition.messaging 패키지의 CompetitionReportRequest는 양수인 대결·참가자 ID와 UUID 목록을 보관한다. JSON에는 competition_id, participant_id, portfolio_reason_ids만 담는다. 생성자는 실제 주문 포함 범위를 판단하지 않으며 전달받은 목록을 변경 불가능하게 복사한다.

```json
{
  "competition_id": 123,
  "participant_id": 456,
  "portfolio_reason_ids": ["550e8400-e29b-41d4-a716-446655440000"]
}
```

AI 참가자마다 위 형식의 메시지를 한 번씩 보낸다. AI 서버는 각 요청으로 별도 보고서를 생성하므로 전체 참가자 수나 모든 요청 도착을 기다리는 신호는 필요하지 않다. account_id도 본문에 포함하지 않는다.

DispatchService가 publish()를 호출할 때 Outbox의 request_id를 requestId 인자로, 요청 객체를 payload 인자로 넘긴다. Publisher는 ID를 생성하지 않는다. AWS SDK가 성공 응답의 messageId를 반환했을 때만 이를 호출자에게 전달한다. 이 메서드는 DB의 Outbox 상태를 직접 변경하지 않는다.

실패 시에는 competition_report_publish_failed 이벤트에 stage=encode 또는 sqs_send, competitionId, participantId, requestId, causeType과 스택 트레이스를 남긴다. 시작 이벤트는 competition_report_publish_started, SQS 성공 응답 이벤트는 competition_report_published이며 성공 로그에 messageId를 포함한다. 본문이나 판단 근거 UUID 목록 전체는 로그에 기록하지 않는다.

성공 로그도 SQS 응답 확인을 의미할 뿐 Outbox SENT 커밋이나 AI 리포트 완성을 뜻하지 않는다. 타임아웃·연결 단절에서는 실제 SQS 전달 여부가 불명확할 수 있어 동일 요청 ID로 재시도하고 AI 소비 측에서도 중복을 방지해야 한다. Standard Queue에는 FIFO 전용 MessageGroupId/MessageDeduplicationId를 설정하지 않는다.

### 설정

| 환경변수 | 기본값·의미 |
|---|---|
| REPORT_QUEUE_ENABLED | 현재 application.yaml 기본 true. true일 때 전송용 빈 생성 |
| REPORT_QUEUE_URL | 배포 환경에서 지정하는 Report Standard Queue URL |
| AWS_REGION | ap-northeast-2 |
| REPORT_DISPATCH_ENABLED | true. queue 활성화 상태에서 자동 실행 여부 |
| REPORT_DISPATCH_INTERVAL_MS | 60000. 이전 실행 종료 후 다음 실행까지 대기 |
| REPORT_DISPATCH_BATCH_SIZE | 20. 조회 한 번당 가져올 수, 허용 범위 1~100. 실행 전체 처리 건수의 상한은 아님 |

CompetitionReportQueueConfiguration이 주문 큐와 별개의 SqsAsyncClient, Publisher, competitionReportTaskScheduler(스레드 1개)를 구성한다. 기본 자격 증명 체인을 사용하며 EC2에서는 IAM Role로 인증한다. 키를 코드에 저장하지 않는다. SDK 전체 호출 제한은 10초, 개별 시도 제한은 5초이며 Publisher는 최대 12초 기다린다. queue를 켜고 dispatch를 끄면 전송 관련 빈만 준비하고 자동 전송은 하지 않는다. 둘 다 켜면 기존 PENDING도 첫 실행부터 전송 대상이 된다.

### 검증 범위

모의 SqsAsyncClient를 사용해 본문 3개 필드와 user_id 제거, Standard Queue 설정, 재시도의 고정 eventId, 실패 예외, messageId 없는 응답 거절을 검증한다. 로컬 H2 DB와 실제 서비스 트랜잭션으로 주문 필터링·중복 ID 제거·과거 null 제외·SENT 저장·실패 롤백·동시 요청 잠금·실패 후보 이후의 배치 순회를 검증한다. 추가 검증은 AI 4명 종료 시 참가자별 요청 생성·반복 완료 중복 방지·두 번째 저장 실패 시 전체 롤백·AI별 판단 근거 분리·한 AI 실패 후 나머지 전송 및 해당 AI만 재시도·잘못된 참가자 연결 거절·회원 탈퇴 삭제 순서다. AI 자동 시작 제한과 분리해 테스트 입력을 진행/완료 상태로 준비한다. 실제 AWS 큐·IAM 권한·AI 소비자와 운영 MySQL 이관은 검증하지 않았다.

## DB 적용

신규 설치는 [신규 DDL](../db/competition-report-outbox.sql), 기존 테이블은 [참가자 이관 SQL](../db/competition-report-outbox-participant.sql)을 사용한다. 기존 요청의 request_id와 상태를 보존하며 단일 AI인 행만 자동 연결한다. 미연결 행이 있으면 중단하고 수동 확인한다. 구·신버전 동시 쓰기와 롤백 제약은 [배포 문서](competition-report-deployment.md)의 참가자별 Outbox 이관 절차를 따른다. SQL 파일은 자동 적용되지 않는다.

## 관련 코드

- [완료 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionService.java)
- [스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionScheduler.java)
- [Outbox 엔티티](../src/main/java/com/stock_spoon/river_be/competition/entity/CompetitionReportOutbox.java)
- [Outbox Repository](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionReportOutboxRepository.java)
- [회원 탈퇴 삭제](../src/main/java/com/stock_spoon/river_be/user/repository/UserDeletionRepository.java)
- [DDL](../db/competition-report-outbox.sql)
- [SQS Publisher](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionReportRequestPublisher.java)
- [요청 본문](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionReportRequest.java)
- [SQS 설정](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionReportQueueConfiguration.java)
- [전송 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionReportDispatchService.java)
- [전송 스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionReportDispatchScheduler.java)
