# AI 간 대결: 전략 전송 성공 후 자동 시작

AI 간 대결은 생성 후 READY로 대기한다. 해당 대결의 전략 전송 Outbox가 SENT로 저장되고 장이 열려 있으면 시작 스케줄러가 IN_PROGRESS로 바꾼다. AI 서버의 준비 완료 통지는 기다리지 않는다.

## 정책 변경과 협업

이전 PR #277의 준비 완료 콜백 방식을 SQS 전달 성공 기준으로 변경했다. 완료 API `/api/v1/competitions/{competitionId}/ai-setup/complete`와 전용 인증 규칙은 제거했다. 종료 리포트 저장 API는 별개이며 그대로다.

AI 파트에는 배포 전에 다음 내용을 공유한다.

> AI 간 대결은 전략 메시지가 SQS에 전달되면 장중에 시작합니다. 준비 완료 콜백은 필요하지 않습니다. 전략 처리가 끝나기 전에도 대결 계좌가 운용 대상으로 조회될 수 있으므로, 미설정 계좌를 건너뛰거나 설정을 마친 뒤 판단하도록 AI 운용 흐름을 확인해 주세요. 기존 event_id 중복 처리도 유지해야 합니다.

SENT는 SQS 전달 성공이다. AI 설정 저장 완료나 첫 주문 성공을 보장하지 않는다. AI 소비 장애가 있어도 대결은 진행 상태가 될 수 있다. 이 정책은 사용자가 승인했으며 실제 AI 운용 동작은 별도 연동 검증이 필요하다.

## 처음 배우는 개발자를 위한 파일별 해설

### 1. 대결을 생성하고 발송 목록을 만든다

[`CompetitionService.createCompetition()`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java)은 참가자의 계좌와 전략을 만든다. 메서드는 특정 일을 묶어 이름을 붙인 코드다.

```java
// AI 간 대결이면 계좌·전략과 메시지 전송 예약을 저장한다.
if (aiVsAi) {
    agentCreation.createParticipants(competition, request.getAgents());
    aiSetupReservation.reserve(competition);
    // 아직 발송 성공을 확인하지 않았으므로 대기 상태로 응답한다.
    return new CompetitionCreateResponse("success", competition.getId(), "ready");
}
```

Outbox는 외부로 보낼 메시지의 발송 목록이다. 대결·계좌·전략·전송 예약을 한 트랜잭션에서 저장하므로 중간 저장이 실패하면 함께 되돌린다.

### 2. 메시지를 보내고 SENT를 기록한다

[`CompetitionAiSetupDispatchService`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupDispatchService.java)가 SQS로 전송한다. SQS는 서버 사이에서 메시지를 전달하는 대기열이다.

```java
// 저장해 둔 메시지 본문을 SQS에 보낸다.
publisher.publish(outbox.getPayload());
// 성공하면 전송 완료로 표시한다. AI 내부 설정 완료라는 뜻은 아니다.
outbox.markSent();
// DB에 변경을 반영한다. 이후 트랜잭션 커밋까지 성공해야 SENT가 확정된다.
outboxes.flush();
```

실패하면 기존 발송 트랜잭션이 롤백되어 PENDING으로 남고 스케줄러가 재시도한다. SQS에는 전달됐지만 DB 커밋이 실패할 수도 있다. 이 경우 재전송이 필요하며 AI 서버는 같은 event_id를 중복 처리하지 않아야 한다.

### 3. 시작 대상을 고른다

[`CompetitionRepository.findWaitingToStart()`](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionRepository.java)는 시작 대기·기간 조건에 더해 AI 대결의 전송 성공 기록을 찾는다. Repository는 DB 조회·저장을 맡는 코드다.

아래는 실제 조회 조건 중 AI 대결 조건을 발췌한 설명용 코드다.

```sql
-- AI 간 대결이고,
c.competitionType = AI_VS_AI
-- 해당 대결과 연결된 전송 성공 기록이 하나 이상 있는지 확인한다.
and exists (
    select o.id from CompetitionAiSetupOutbox o
    where o.competition = c and o.status = SENT
)
```

사용자 vs AI의 기존 시작 정책은 유지한다. PENDING, CANCELLED, 전송 기록 없음은 AI 시작 조건을 만족하지 않는다. 준비 완료 시각이 있더라도 SENT가 아니면 시작하지 않는다.

### 4. 장이 열렸을 때 상태를 변경한다

[`CompetitionStartScheduler`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionStartScheduler.java)는 앞선 실행 완료 후 기본 10초를 기다렸다가 실행된다. 조회할 때 대결 잠금을 잡아 여러 서버가 동시에 같은 대결을 시작하지 않도록 한다.

```java
// 장이 닫혀 있으면 이번 실행을 끝낸다.
if (!tradingHours.isOpen(now)) return;
for (var competition : competitions.findWaitingToStart(now.toLocalDate())) {
    // DB 잠금을 기다리는 사이 장이 닫힐 수 있어 현재 시각을 다시 읽는다.
    var startTime = tradingHours.now();
    if (!tradingHours.isOpen(startTime)
            || startTime.toLocalDate().isBefore(competition.getStartDate())
            || !startTime.toInstant().isBefore(competition.tradingEndsAt())) continue;
    // 조건을 만족하면 실제 시작 시각과 IN_PROGRESS를 기록한다.
    competition.start(startTime.toInstant());
}
```

[`Competition.start()`](../src/main/java/com/stock_spoon/river_be/competition/entity/Competition.java)는 READY 상태인지 검사한 뒤 시작 시각을 저장하고 상태를 바꾼다. 이 메서드가 DB에 직접 전송 기록을 조회하지는 않는다. 자동 시작의 전송 검사는 Repository가 담당한다.

[`CompetitionTradingHours`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionTradingHours.java)는 한국 시간 평일 09:00 이상 15:30 미만을 장중으로 본다. 공휴일은 아직 반영하지 않는다. 주말 종료일의 실제 거래 종료는 직전 금요일 15:30이다.

### 5. 시작 후 계좌가 운용 대상으로 노출된다

[`AccountRepository`](../src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java)와 [`AiUserSnapshotService`](../src/main/java/com/stock_spoon/river_be/user/service/AiUserSnapshotService.java)는 진행 중이고 날짜 조건을 만족하는 대결 계좌를 기존 방식으로 제공한다. 실제 첫 매매 시각은 AI 서버의 설정·조회·판단 주기에 달려 있다.

## 기존 대결과 배포

| 상태 | 새 코드 배포 후 처리 |
|---|---|
| READY + SENT, 거래 기간 남음 | 장중 스케줄러에서 시작 |
| READY + PENDING | 실제 발송과 SENT 커밋까지 대기 |
| READY + 전송 기록 없음 | 시작하지 않음. 기존 전략·계좌 연결을 확인하고 별도 복구 필요 |
| CANCELLED 또는 실제 거래 종료 시각 경과 | 시작하지 않음 |
| ai_setup_completed_at만 존재, SENT 없음 | 시작하지 않음 |

종료일은 대기한 만큼 연장하지 않는다. PENDING을 수동으로 SENT로 바꾸면 실제 발송 확인 없이 시작할 수 있으므로 정상 전송 재시도 경로를 사용한다. 기존 대결의 일괄 재전송·수동 상태 변경은 이번 작업에서 수행하지 않았다.

DB 스키마 변경은 없다. 기존 `competitions.ai_setup_completed_at`은 코드 매핑과 사용을 제거하지만 실제 DB에서는 삭제하지 않는다. 이미 추가한 nullable 컬럼은 남아 있어도 된다. 아직 추가하지 않았다면 이 SENT 방식 배포에는 추가할 필요가 없다. 과거 SQL `db/competition-ai-setup-completion.sql`은 이력으로 남기며 이번 배포에서는 실행하지 않는다. 추후 컬럼 삭제가 필요하면 인프라 팀원을 통해 별도 협의·적용한다.

배포 순서: AI 파트에 정책 전달 및 미설정 계좌 처리 확인 → BE 배포 → 기간 내 기존 SENT 대결 시작 확인 → AI 계좌 조회·실제 운용 확인. SQL 적용·운영 DB 수정이나 AI 서버 저장소 수정은 이번 로컬 구현에 포함하지 않는다.

## 검증

`CompetitionAiStartTests`는 SENT 시작, AI 2~4명, PENDING·CANCELLED·기록 없음, 과거 SENT, 기간 만료와 동시 실행을 검증한다. `CompetitionStartSchedulerBoundaryTests`는 잠금 대기 후 장 마감과 날짜 경과를 검사한다. 기존 생성·발송·주문·정산·인증 회귀도 함께 검사한다. 로컬 테스트 통과와 서비스 전체 연동 완료는 구분한다.
