# 장중 AI 대결 생성 즉시 전송·시작

AI 간 대결을 장중 생성해도 전송 스케줄러(기본 60초)와 시작 스케줄러(기본 10초)를 기다리던 흐름에 빠른 경로를 추가한다. 생성 DB 커밋 → 즉시 SQS 발송 및 SENT 커밋 → 해당 대결 시작 순서다. 무조건 시작을 보장하는 것은 아니며 전송 실패나 장 마감 시 READY를 반환한다.

## 파일별 해설

### 1. Controller는 생성 조율 서비스를 호출한다

`CompetitionController.createCompetition(jwt, request)`는 기존 JWT와 DTO 검증을 거쳐 `CompetitionCreationCoordinator.create(userId, request)`를 호출한다. Coordinator는 순서를 조율하는 담당자다. 전체를 트랜잭션으로 묶지 않으므로 기존 생성 서비스 프록시가 DB 커밋을 완료한 후 다음 단계로 넘어간다.

```java
// 생성 서비스의 트랜잭션 프록시가 커밋까지 끝낸 후 반환한다.
var result = creation.createCompetition(userId, request);
// 사용자 vs AI는 기존 생성 결과를 그대로 반환한다.
if (!"ai_vs_ai".equals(request.getCompetitionType())) return result;
```

생성 자체의 실패는 기존 오류로 반환한다. 생성 데이터가 확정된 후의 전송·시작 실패를 생성 실패로 응답하지 않는다. 외부 호출자가 상위 트랜잭션을 열어 생성 커밋이 아직 끝나지 않은 상황은 빠른 경로를 건너뛰고 스케줄러에 맡긴다.

### 2. 예약된 메시지를 즉시 전송한다

장중이고 큐 기능이 켜져 있으면 `CompetitionAiSetupDispatchService.dispatch(outboxId)`를 호출한다. 기존 대결 우선 잠금, 취소·중복 검사와 고정 event_id를 재사용한다. 별도 서비스 프록시가 SENT 커밋까지 완료한다. 큐 기능이 꺼져 있거나 장외면 즉시 전송하지 않는다. dispatch.enabled는 기존 주기 전송 스케줄러의 설정이며, 큐가 켜진 장중 생성의 즉시 전송 여부와는 구분한다.

```java
// 방금 생성한 대결의 발송 예약을 찾는다.
var outbox = outboxes.findByCompetitionId(result.getCompetitionId()).orElseThrow();
// 기존 전송 트랜잭션을 실행하고 커밋 후 반환받는다.
sender.dispatch(outbox.getId());
```

SQS 발행은 기존 `.get(12, TimeUnit.SECONDS)` 제한을 재사용한다. 전체 HTTP 요청의 상한은 아니다. DB 잠금·조회·네트워크 처리 시간이 추가되므로 FE와 프록시의 요청 타임아웃도 확인해야 한다. 응답 유실 시 재생성하지 말고 목록에서 생성 결과를 확인한다.

### 3. 한 건을 잠그고 시작한다

Coordinator의 별도 READ_COMMITTED 트랜잭션에서 생성한 대결을 잠그고 SENT 기록을 확인한다. `CompetitionStartScheduler.startIfEligible(competition, hours)`는 즉시 처리와 기존 스케줄러가 공유하는 상태·시간 검사다.

```java
// 이미 시작·취소·완료되었거나 장외·기간 밖이면 시작하지 않는다.
if (competition.getState() != Competition.State.READY
        || !hours.isOpen(now) || now.toLocalDate().isBefore(competition.getStartDate())
        || !now.toInstant().isBefore(competition.tradingEndsAt())) return;
// 시작 시각과 IN_PROGRESS를 저장한다.
competition.start(now.toInstant());
```

즉시 경로와 복구 스케줄러는 같은 대결 잠금을 사용한다. 전송·시작 사이 취소되거나 먼저 시작돼도 상태 검사로 중복 전환하지 않는다. 장 마감 직전 요청도 잠금을 획득한 후 현재 시간을 다시 확인한다.

### 4. 현재 상태를 응답한다

즉시 시도 후 소유자의 대결 상태를 다시 읽어 `201 Created` 응답에 포함한다. 전송 실패는 PENDING으로 롤백되며 대결은 보존된다. 시작 실패 시 SENT는 남아 이후 스케줄러가 복구한다. 상태 재조회까지 실패하면 생성 서비스의 ready 응답으로 대체하며 실제 상태는 목록에서 재확인해야 한다. 이 대체 응답과 실제 DB 상태가 잠시 다를 수 있다.

| 상황 | 처리 |
|---|---|
| 장중 + 전송·시작 성공 | in_progress 응답 |
| 장외 또는 큐 비활성 | ready, 주기 경로 대기 |
| 전송 실패·타임아웃 | 생성 보존, PENDING 재시도 |
| 발송 후 장 마감 | SENT + READY, 다음 장중 시작 |
| 취소·동시 실행 | 잠금 후 실제 상태 유지·조회 |

## 협업·배포 범위

새 DB 스키마 변경은 없다. 과거 ai_setup_completed_at 컬럼은 그대로 두며 새 AI 완료 콜백도 없다. AI 파트에는 전략 처리 전 계좌가 더 빨리 노출될 수 있으므로 미설정 계좌 처리와 event_id 중복 방지를 확인하도록 공유해야 한다. FE에는 생성 결과가 바로 in_progress일 수 있음과 요청 대기 시간, READY가 항상 장외를 뜻하지 않는다는 점을 공유한다. 기존 null 수익률/toFixed 화면 오류 해결 여부도 별도 확인한다. 이 작업에서 외부 파트에 메시지를 보내거나 실제 AWS·DB·서비스 배포를 수행하지 않았다.

## 검증 범위

CompetitionImmediateCreationTests는 AI 2~4명, 독립 트랜잭션에서 생성 데이터 가시성, 발행 실패, 전송 중 장 마감과 장외 생성 동작을 검사한다. SQS publisher는 테스트 대역이므로 실제 AWS 소비·AI 운용 성공을 증명하지 않는다. 기존 생성·복구·인증·DB 쓰기 재생 관련 회귀 검사도 실행한다.

2026-11-11 로컬 `gradlew.bat test spotbugsMain` 결과: 전체 810개, 실패·오류 0개, 건너뜀 24개. SpotBugs와 git diff --check 통과. 별도 코드 리뷰에서 코드 수정이 필요한 지적은 없었고 주기 발송 중지 설정의 문서 표현을 정리했다. 테스트에서 커밋 여부를 확인하는 부분의 Java 제네릭 타입 추론 모호성을 Boolean 변수로 명확히 한 뒤 전체 검증을 통과했다. 앱 코드 커밋·푸시·PR 생성 및 배포는 이 구현 작업에서 수행하지 않았다.
