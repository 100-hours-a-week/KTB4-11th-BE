# AI 간 대결 준비 완료와 자동 시작

AI 간 대결은 생성 후 READY로 대기한다. AI 서버가 모든 참가자의 전략을 영구 저장한 뒤 준비 완료를 통지하면, BE가 이를 기록하고 다음 장중 시작 스케줄러에서 IN_PROGRESS로 바꾼다. 메시지를 SQS에 보낸 것만으로 시작하지 않는다.

## API 계약

`POST /api/v1/competitions/{competitionId}/ai-setup/complete`

```json
{"event_id":"a5af5f21-9bde-4d03-895d-dfe7d8340211"}
```

- event_id는 생성 당시 SQS 메시지의 UUID를 그대로 사용한다. 새로 발급하지 않는다.
- 인증은 기존 리포트 저장과 동일하다. `actor=AI`, `type=access`, subject=대결 소유 사용자 ID인 서명된 JWT를 `access_token` 쿠키로 보낸다. 전역 `ai-server` 토큰과 일반 사용자 토큰은 허용하지 않는다. Authorization 헤더는 인증 경로가 아니다.
- CSRF가 활성화돼 있으면 기존 `/api/v1/auth/csrf` 응답의 XSRF 쿠키와 `X-XSRF-TOKEN` 헤더도 보낸다.
- 정상·동일 이벤트 중복 통지: 204, 응답 본문 없음. 최초 완료 시각은 보존한다. 시작·종료 후 동일 이벤트 재시도도 204다.
- 요청 누락·잘못된 UUID: 400. 인증 실패: 401, 권한·CSRF 실패: 403.
- 대결 미존재·다른 소유자: 404 COMPETITION_NOT_FOUND.
- 다른 이벤트: 404 AI_SETUP_REQUEST_NOT_FOUND.
- 사용자 vs AI: 409 COMPETITION_TYPE_NOT_SUPPORTED.
- 취소: 409 COMPETITION_CANCELLED. 최초 완료 통지 시 실제 거래 종료 시각 경과: 409 COMPETITION_SETUP_EXPIRED.

이 API는 모든 참가자가 준비됐다는 AI 서버의 통지를 신뢰한다. AI 서버는 일부 참가자만 설정한 상태에서 호출하면 안 된다. 참가자별 준비 통지를 합산하는 API는 아니다.

## 코드 역할과 처리 순서

1. CompetitionService는 참가자·계좌·전략과 Outbox를 같은 트랜잭션에 저장한다.
2. CompetitionAiSetupDispatchService는 기존 방식으로 SQS에 전달한다. SENT는 전달 완료다.
3. CompetitionAiSetupCallbackController가 완료 요청을 받고 CompletionService가 소유권·유형·event_id·취소·기간을 검사한다.
4. competitions.ai_setup_completed_at에 BE 수신 시각을 기록한다. 콜백은 대결을 직접 시작하지 않는다.
5. CompetitionRepository.findWaitingToStart는 기존 사용자 대결과 준비 완료된 AI 대결을 조회한다. CompetitionStartScheduler가 평일 09:00 이상 15:30 미만 및 실제 거래 종료 전인지 확인해 시작한다.
6. 시작 후 기존 AI snapshot의 운용 대상 조회에 참가 계좌가 포함된다. 실제 첫 투자 판단 실행 시각은 AI 서버의 조회·운용 주기에 달려 있다.

완료·발송·시작·취소는 대결 잠금을 먼저 사용한다. 완료 통지는 해당 이벤트의 Outbox를 SENT로도 표시한다. AI 소비가 확인되므로, 발송 후 DB 커밋 실패로 PENDING만 남은 경우 재전송을 멈출 수 있다. 발송 처리 역시 같은 대결 잠금을 거치므로 동시 수정과 순서가 맞는다.

## 처음 접하는 개발자를 위한 코드 해설

대학 팀 발표를 생각하면 순서를 이해하기 쉽다. **대결을 만드는 것**은 발표 팀을 만드는 일이다. **전략을 전송하는 것**은 팀원에게 할 일을 전달하는 일이고, **준비 완료 통지**는 모든 팀원이 준비됐다고 답하는 일이다. 답을 받은 뒤 **장이 열리면** 발표를 시작하듯 대결을 시작한다.

이 기능을 추가하기 전에는 AI 간 대결이 시작 대상 조회에서 빠졌고, 대결의 `start()` 메서드도 AI 간 대결을 거절했다. 다수 AI 운용을 단계적으로 개발하는 동안 준비되지 않은 대결이 시작되지 않게 둔 제한이었다. 지금은 **전체 AI 준비를 확인한 대결만** 시작 대상에 포함한다.

아래 코드들은 이해를 돕기 위해 실제 코드의 일부에 설명 주석을 추가한 것이다. 생략한 코드는 각 파일에서 확인할 수 있다.

### 1. 대결과 참가자를 만든다

[`CompetitionService.createCompetition()`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java)은 사용자가 보낸 대결 생성 요청을 처리하는 메서드다. 메서드는 특정 동작을 묶어 이름을 붙인 코드다.

```java
// AI 간 대결일 때 이 블록을 실행한다.
if (aiVsAi) {
    // AI 참가자들의 계좌와 전략을 저장한다.
    agentCreation.createParticipants(competition, request.getAgents());

    // AI 서버로 설정을 보내기 위한 예약을 저장한다.
    aiSetupReservation.reserve(competition);

    // 생성 성공과 대기 상태를 응답하고 메서드를 끝낸다.
    return new CompetitionCreateResponse("success", competition.getId(), "ready");
}
```

생성 시점에는 AI 서버의 설정이 끝나지 않았으므로 `ready`를 반환한다. 생성한 대결과 계좌, 전략, 전송 예약은 한 트랜잭션에서 저장한다. 트랜잭션은 여러 저장 작업을 하나로 묶어 중간에 실패하면 함께 되돌리는 방식이다.

### 2. 메시지 전송과 AI 준비 완료를 구분한다

[`CompetitionAiSetupOutbox`](../src/main/java/com/stock_spoon/river_be/competition/entity/CompetitionAiSetupOutbox.java)는 보낼 메시지를 기록하는 발송 목록이다. `PENDING`은 전송 대기, `SENT`는 전달 완료, `CANCELLED`는 전송 전 취소를 뜻한다. SQS는 서버 사이에서 메시지를 전달하는 대기열이다.

[`CompetitionAiSetupDispatchService`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupDispatchService.java)의 주요 처리다.

```java
// 발송 목록에 저장된 본문을 SQS에 보낸다.
publisher.publish(outbox.getPayload());

// SQS에 보낸 사실을 기록한다. AI 서버의 설정 완료를 뜻하지는 않는다.
outbox.markSent();
```

택배를 접수했다고 상대방이 상자를 열어 사용 준비까지 마친 것은 아닌 것과 같다. 발송 후 DB 기록이 실패하면 같은 메시지가 다시 전달될 수 있으므로 AI 서버는 `event_id`로 중복을 구분해야 한다.

### 3. AI 서버의 완료 통지를 받는다

[`CompetitionAiSetupCallbackController`](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionAiSetupCallbackController.java)는 HTTP 요청을 받는 접수 창구다. 요청 본문은 [`CompetitionAiSetupCompleteRequest`](../src/main/java/com/stock_spoon/river_be/competition/dto/CompetitionAiSetupCompleteRequest.java)가 검사한다. 실제 업무 처리는 [`CompetitionAiSetupCompletionService`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupCompletionService.java)가 맡는다.

```java
// 완료 API에 도착한 요청을 받는다.
@PostMapping("/{competitionId}/ai-setup/complete")
@ResponseStatus(HttpStatus.NO_CONTENT) // 정상 응답은 204이며 본문이 없다.
public void complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long competitionId,
                     @Valid @RequestBody CompetitionAiSetupCompleteRequest request) {
    // 토큰의 사용자 ID, 대결 ID, 요청을 서비스에 전달한다.
    completion.complete(Long.parseLong(jwt.getSubject()), competitionId, request);
}
```

서비스는 소유자, 대결 유형, 원래 보낸 `event_id`, 취소 여부와 실제 거래 종료 시각을 확인한다. 이미 같은 완료 통지를 처리했다면 첫 완료 시각을 유지하고 정상 응답한다. 완료 통지는 **모든 참가자의 설정이 저장됐을 때** AI 서버가 보내야 한다. BE가 참가자별 완료 메시지를 따로 합산하는 방식은 아니다.

[`SecurityConfig`](../src/main/java/com/stock_spoon/river_be/config/SecurityConfig.java)는 누가 API에 접근할 수 있는지 정한다. 이 요청에는 대결 소유자의 사용자 ID가 들어 있는 AI 서버 토큰을 요구한다. 완료 서비스에서도 해당 사용자가 대결 소유자인지 다시 확인한다.

### 4. 준비 완료 시각을 대결에 저장한다

[`Competition`](../src/main/java/com/stock_spoon/river_be/competition/entity/Competition.java)은 대결 한 건의 데이터와 상태 변경 규칙을 담는 클래스다. 데이터베이스의 `competitions` 테이블과 연결된다.

```java
// 모든 AI의 설정 완료 통지를 받은 시각이다.
// null이면 아직 준비 완료를 확인하지 못한 상태다.
@Column(name = "ai_setup_completed_at")
private Instant aiSetupCompletedAt;
```

`start()`는 대결이 `READY`인지 확인한다. AI 간 대결이면 여기에 더해 `aiSetupCompletedAt`이 있는지도 확인한다. 사용자 vs AI 대결에는 이 AI 간 대결 준비 조건을 적용하지 않는다.

이 값을 저장할 칸을 DB에도 만들어야 하므로 [`competition-ai-setup-completion.sql`](../db/competition-ai-setup-completion.sql)을 작성했다. 기존 데이터의 기본값은 `NULL`이며 기존 `SENT`를 준비 완료로 추정하지 않는다. **이 SQL은 수동 적용용이며 실제 DB 적용 여부는 별도로 확인해야 한다.**

### 5. 시작할 대결을 찾고 장 시간을 확인한다

[`CompetitionRepository.findWaitingToStart()`](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionRepository.java)는 데이터베이스에서 시작할 대결을 찾는다. Repository는 데이터 조회·저장을 담당하는 코드다.

```java
// 시작 대기 상태이며,
// 사용자 vs AI이거나 AI 간 대결의 준비 완료 시각이 있는 경우를 찾는다.
// 시작일과 종료일 사이에 오늘이 포함돼야 한다.
@Query("select c from Competition c where c.state = READY "
        + "and (c.competitionType = USER_VS_AI or (c.competitionType = AI_VS_AI and c.aiSetupCompletedAt is not null)) "
        + "and c.startDate <= :today and c.endDate >= :today order by c.id")
List<Competition> findWaitingToStart(java.time.LocalDate today);
```

실제 코드의 조회에는 여러 서버가 같은 대결을 동시에 수정하지 않도록 잠금도 설정돼 있다. 잠금은 한 작업이 처리하는 동안 다른 작업이 같은 기록을 수정하지 못하도록 기다리게 하는 장치다.

[`CompetitionStartScheduler`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionStartScheduler.java)는 주기적으로 실행되는 코드다. 기본 설정은 앞선 실행이 끝난 후 10초를 기다리는 방식이다.

```java
var now = tradingHours.now();
// 장이 닫혀 있으면 이번 실행을 끝낸다.
if (!tradingHours.isOpen(now)) return;

// 시작 후보를 가져와 한 대결씩 검사한다.
for (var competition : competitions.findWaitingToStart(now.toLocalDate())) {
    // DB 잠금을 기다리는 동안 장이 닫힐 수 있으므로 시간을 다시 읽는다.
    var startTime = tradingHours.now();
    if (!tradingHours.isOpen(startTime)
            || startTime.toLocalDate().isBefore(competition.getStartDate())
            || !startTime.toInstant().isBefore(competition.tradingEndsAt())) continue;

    // 조건을 만족하면 실제 시작 시각을 기록하고 IN_PROGRESS로 바꾼다.
    competition.start(startTime.toInstant());
}
```

[`CompetitionTradingHours`](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionTradingHours.java)는 한국 시간으로 평일 09:00 이상 15:30 미만을 장중으로 판단한다. 현재 공휴일은 고려하지 않는다. 주말 종료일은 직전 금요일 15:30을 실제 거래 종료로 계산한다.

### 6. 시작 후 AI 계좌를 운용 대상으로 보여 준다

[`AccountRepository.findActiveAiManagedOn()`](../src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java)은 AI가 운용할 계좌를 조회한다. 대결 계좌는 진행 중이고 날짜 조건을 만족해야 포함된다. [`AiUserSnapshotService`](../src/main/java/com/stock_spoon/river_be/user/service/AiUserSnapshotService.java)는 그 계좌들의 잔액·보유 종목·대기 주문 등을 AI 서버에 제공한다.

예를 들어 전날 18시에 대결이 만들어지고 모든 AI가 18시 1분에 준비되면, 대결은 다음 평일 장이 열릴 때까지 `READY`로 기다린다. 09시 이후 스케줄러가 시작 처리하면 계좌가 운용 대상에 포함될 수 있다. AI가 실제 첫 주문을 내는 시각은 AI 서버가 조회하고 판단하는 주기에 달려 있다.

### 7. 무엇을 검사했는가

[`CompetitionAiStartTests`](../src/test/java/com/stock_spoon/river_be/competition/CompetitionAiStartTests.java)는 AI 2~4명, 준비 전 대기, 장중 시작, 계좌 노출, 중복 통지, 취소, 잘못된 이벤트와 소유자 등을 검사한다. [`CompetitionStartSchedulerBoundaryTests`](../src/test/java/com/stock_spoon/river_be/competition/CompetitionStartSchedulerBoundaryTests.java)는 DB 잠금 대기 중 장이 닫힌 상황을 검사한다. [`SecurityJwtTests`](../src/test/java/com/stock_spoon/river_be/config/SecurityJwtTests.java)는 실제 서명된 테스트 토큰으로 인증과 권한을 확인한다.

로컬 전체 테스트 결과는 804개 중 실패·오류 0개, 건너뜀 24개였고 SpotBugs도 통과했다. 이 결과는 BE 로컬 검증이다. 실제 AI 서버·AWS·운영 DB가 연결되어 성공했음을 뜻하지는 않는다.

## 시간·실패 정책

- 전날 18시 준비 완료 → 다음 평일 09시 이후 스케줄러에서 시작.
- 당일 10시 준비 완료 → 그 이후 스케줄러에서 시작. 기본 fixed delay는 10초이며 부하·설정에 따라 지연될 수 있다.
- 설정 실패·완료 통지 지연 → READY 유지. SQS 전송 실패는 기존 Outbox 재시도, HTTP 응답 유실·5xx는 AI가 같은 event_id로 재시도한다.
- 종료일은 연장하지 않는다. 시작 전 실제 종료 시각을 넘기면 READY로 남고 기존 취소 API를 사용할 수 있다. 자동 취소나 새 실패 상태는 추가하지 않았다.
- 주말 종료는 직전 금요일 15:30이 실제 종료다. 공휴일은 기존 시간 정책과 동일하게 미반영이다.

## 적용 순서와 외부 연동

1. `db/competition-ai-setup-completion.sql`을 대상 DB에 수동 적용한다. 기존 컬럼 유무를 확인하고 한 번만 실행한다. 자동 실행 SQL이 아니다.
2. BE를 배포한다. 기존 READY/SENT 대결은 준비 완료로 소급 변환하지 않는다.
3. AI 서버에서 전체 참가자 설정 저장, event_id 중복 방지, 완료 콜백 및 재시도를 구현·검증한다.
4. 개발 환경에서 생성 → 설정 전달 → 전체 준비 완료 → 시작 → AI 계좌 조회 → 주문·성과·정산·리포트 흐름을 확인한 뒤 운영에 적용한다.

이 작업은 BE 구현과 로컬 검증이다. AI 저장소 수정, 실제 AWS 소비·발행 검증, 개발·운영 DB SQL 적용 및 배포는 포함하지 않는다. AI 서버의 완료 호출이 연결되기 전에는 AI 대결이 계속 READY인 것이 정상이다.
