# AI 전략 설정 SQS 전송 (#260)

AI 대결 생성 시 저장한 전체 전략을 메시지 하나로 AI 서버에 전달한다. 생성 응답은 기존 ready를 유지하며 SQS 응답을 기다리지 않는다.

## 호출 흐름

아래 클래스의 패키지는 `com.stock_spoon.river_be`를 기준으로 표시한다. **대결 생성 요청 안에서는 DB에 전송 예약까지 저장**하고, **SQS 발송은 나중에 별도 스케줄러가 실행**한다.

### 1. 대결 생성과 전송 예약 저장

```text
POST /api/v2/users/me/competitions
  → 인증된 Jwt를 jwt에 주입
  → 요청 JSON을 CompetitionCreateRequest request로 변환하고 @Valid 검사
  → competition.controller.CompetitionController.createCompetition(jwt, request)
    → Long.parseLong(jwt.getSubject()) → userId
    → competition.service.CompetitionService.createCompetition(userId, request)
      [하나의 생성 트랜잭션 시작]
      → validator.validate(request), tradingHours.now()
      → UserRepository.findLockedById(userId)
      → 온보딩·대결 유형·AI 수·전략 개수·날짜·이름 중복 검사
      → CompetitionRepository.save(new Competition(...)) → competition
      → ai_vs_ai일 때:
        → CompetitionAgentCreationService.createParticipants(competition, request.getAgents())
          → 각 입력의 테마 정리·중복/관심·제외 충돌 검사
          → AccountRepository.save(new Account(..., true)) → account
          → CompetitionParticipantRepository.save(new CompetitionParticipant(competition, account))
            → participant
          → CompetitionAgentRepository.save(new CompetitionAgent(participant, i, ...))
        → CompetitionAiSetupReservationService.reserve(competition)
          → UUID.randomUUID().toString() → eventId
          → CompetitionAgentRepository
              .findAllByParticipantCompetitionIdOrderByPositionAsc(competition.getId())
          → 저장된 각 전략을 CompetitionAiSetupRequest.Agent로 변환
            (participantId, accountId, riskLevel, preferredThemes,
             excludedThemes, additionalRequest)
          → 설정 개수와 competition.getAiParticipantCount() 일치 확인
          → new CompetitionAiSetupRequest(eventId, "competition_ai_setup",
                                          competition.getId(), settings)
          → JsonMapper.writeValueAsString(message) → 고정 JSON 본문
          → CompetitionAiSetupOutboxRepository.save(
              new CompetitionAiSetupOutbox(competition, eventId, JSON 본문))
        → new CompetitionCreateResponse("success", competition.getId(), "ready")
      [DB 커밋 후 컨트롤러에 반환]
  → HTTP 201 Created
    {"message":"success", "competition_id": 생성된 ID, "state":"ready"}
```

인증된 JWT(사용자를 확인하는 토큰)는 컨트롤러의 `jwt`에 주입된다. 요청 본문을 DTO(요청 데이터를 담는 객체)로 변환하는 작업과 `@Valid` 입력 검사는 토큰 인증과 별도로 수행된다. 컨트롤러는 JWT의 subject를 숫자 사용자 ID로 바꿔 `CompetitionService.createCompetition(userId, request)`에 전달한다.

생성 서비스는 사용자와 대결 조건을 검사하고 대결을 저장한다. AI 간 대결이면 `CompetitionAgentCreationService.createParticipants()`에 대결과 입력 전략 목록을 넘긴다. 이 메서드는 각 전략을 정리한 뒤 AI 계좌, 그 계좌에 연결된 참가자, 그 참가자의 전략을 순서대로 저장한다. `i`는 입력 순서이며 저장된 전략의 위치가 된다.

이후 `CompetitionAiSetupReservationService.reserve()`는 **입력 JSON을 그대로 복사하는 대신 방금 저장한 전략을 위치 순서로 다시 조회**한다. 참가자 ID와 계좌 ID는 저장된 연결에서 읽고, 테마·추가 요청은 정리된 저장값을 사용한다. 전체 설정을 한 DTO에 묶어 JSON으로 변환하고, 고유한 `eventId`와 본문을 아웃박스(전송 예약 테이블)에 함께 저장한다.

참가자 생성과 예약 저장 메서드는 `MANDATORY`이므로 이미 실행 중인 생성 트랜잭션에 참여한다. 예약 저장까지 성공하면 커밋 후 기존 생성 응답을 반환한다. 저장·JSON 변환 등이 실패하면 해당 생성에서 저장한 대결·계좌·참가자·전략·예약을 모두 되돌린다. 이 경로에서는 SQS를 호출하지 않는다. 사용자 vs AI 분기는 기존 계좌·참가자 생성 경로로 진행하며 전략 전송 예약을 만들지 않는다.

코드: [컨트롤러](../src/main/java/com/stock_spoon/river_be/competition/controller/CompetitionController.java), [생성 서비스](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java), [참가자 생성](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAgentCreationService.java), [전송 예약](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupReservationService.java), [메시지 DTO](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionAiSetupRequest.java).

### 2. 저장한 예약을 SQS로 발송

```text
competition.ai-setup.queue.enabled=true일 때 전송 구성 등록
  → competitionAiSetupTaskScheduler 전용 스레드에서
    CompetitionAiSetupDispatchScheduler.dispatchPendingSetups()
    [fixedDelay: 직전 실행 완료 후 기본 60초]
    → dispatch.enabled=false이면 이번 실행 종료
    → CompetitionAiSetupOutboxRepository.findPendingUpperBound() → upperId
    → findPendingIdsBetween(afterId, upperId, PageRequest.of(0, batchSize))
      → PENDING 예약 ID를 오름차순으로 조회 (기본 조회 묶음 20개)
    → 각 id에 대해 별도 서비스 프록시 호출:
      CompetitionAiSetupDispatchService.dispatch(id)
      [예약 한 건의 READ_COMMITTED 트랜잭션 시작]
      → outboxes.findById(id) → candidate (없으면 null 반환)
      → competitions.findLockedById(candidate.getCompetition().getId())
      → outboxes.findLockedById(id) → outbox
      → EntityManager.refresh(outbox) → 잠금 대기 후 최신 상태 확인
      → PENDING이 아니면 null 반환
      → 대결 CANCELLED이면 outbox.markCancelled(), null 반환
      → AI_VS_AI 및 READY가 아니면 예외
      → CompetitionAiSetupRequestPublisher.publish(outbox.getPayload())
        → 본문 공백·UTF-8 크기 검사
        → SendMessageRequest(queueUrl, messageBody=저장된 본문)
        → SqsAsyncClient.sendMessage(request).get(12, TimeUnit.SECONDS)
        → 성공 응답의 messageId 확인
      → outbox.markSent()
      → outboxes.flush()
      → outbox.getEventId() 반환
      [커밋 후 스케줄러에 반환]
    → 반환된 eventId를 requestId 변수로 받아 성공 로그 기록
    → afterId=id로 갱신하고 다음 예약/다음 묶음 처리
    → upperId까지 처리하면 이번 실행 종료
```

발송 스케줄러는 생성 요청과 독립적으로 실행한다. `queue.enabled`가 꺼져 있으면 전송 구성 자체가 등록되지 않지만 생성 시 예약은 계속 저장된다. 켜져 있으면 기본 60초 간격으로 실행하며, `dispatch.enabled`로 발송만 일시 중지할 수 있다. `batch-size`는 한 실행의 총 발송 제한이 아니라 **한 번에 조회하는 묶음 크기**다. 실행 시작 시 존재한 미전송 예약의 마지막 ID를 `upperId`로 잡고 그 범위까지 순회한다.

각 ID는 `CompetitionAiSetupDispatchService.dispatch(id)`에 전달된다. 이 메서드는 한 건마다 별도 트랜잭션을 열고 대결, 예약 순서로 잠금을 잡는다. 취소 처리도 대결을 먼저 잠그므로 취소와 발송의 순서를 조정할 수 있다. 예약은 잠금 후 다시 읽어 이미 다른 실행이 처리했는지 확인한다. 취소된 대결의 예약은 `CANCELLED`로 저장하고 SQS를 호출하지 않는다.

전송 가능한 예약이면 Publisher에 저장된 JSON 문자열을 넘긴다. Publisher는 환경 설정의 큐 URL과 본문으로 AWS SDK 전송 요청을 만들고, 최대 12초 동안 결과를 기다린다. `messageId`가 있는 성공 응답을 받아야 정상 반환한다. **대결 생성 요청은 기다리지 않지만, 이 별도 발송 트랜잭션은 SQS 응답을 기다리는 동안 잠금을 유지한다.**

정상 반환 후 예약을 `SENT`로 바꾸고 DB에 반영한다. 서비스 트랜잭션 커밋까지 성공한 다음 스케줄러가 성공 로그를 남긴다. 이때 코드의 `requestId` 변수와 로그 값은 새 ID가 아니라 예약의 `eventId`다. AI 서버가 메시지를 읽고 전략 설정을 완료했는지 확인하는 단계는 이 흐름에 구현되어 있지 않다.

코드: [전송 구성](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionAiSetupQueueConfiguration.java), [스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupDispatchScheduler.java), [개별 발송](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionAiSetupDispatchService.java), [SQS Publisher](../src/main/java/com/stock_spoon/river_be/competition/messaging/CompetitionAiSetupRequestPublisher.java), [예약 조회](../src/main/java/com/stock_spoon/river_be/competition/repository/CompetitionAiSetupOutboxRepository.java), [예약 상태](../src/main/java/com/stock_spoon/river_be/competition/entity/CompetitionAiSetupOutbox.java).

### 전달값과 실패 시 다음 동작

| 경계 | 전달값 | 결과와 실패 처리 |
|---|---|---|
| 컨트롤러 → 생성 서비스 | JWT에서 읽은 userId, 요청 DTO | 조건 오류는 기존 생성 검증으로 거절. 생성 트랜잭션 실패 시 부분 저장 방지 |
| 생성 서비스 → 예약 서비스 | 저장된 Competition | 전략 조회 → 고정 eventId·본문 저장. 예약 실패도 생성 전체 롤백 |
| 스케줄러 → 발송 서비스 | 전송 예약의 id | 이미 처리됐으면 건너뜀, 취소 대결이면 예약도 CANCELLED |
| 발송 서비스 → Publisher | outbox.getPayload() | 저장된 본문 그대로 전송. 새 eventId나 본문을 만들지 않음 |
| Publisher → SQS | queueUrl, messageBody | 타임아웃·전송 오류·messageId 누락은 예외. 발송 트랜잭션 롤백 후 다음 주기에 재시도 |
| 발송 서비스 → 스케줄러 | 성공 시 eventId, 건너뛰면 null | 성공 커밋 후 로그. 예외는 실패 로그를 남기고 다음 ID로 진행 |

예를 들어 예약 10번의 SQS 전송이 실패해도 스케줄러는 11번으로 이동한다. 10번은 `PENDING`으로 남아 다음 실행 때 같은 본문과 `event_id`로 다시 시도한다. 후보 목록 조회 자체가 실패하면 실행 실패 로그를 남기고 다음 주기에 다시 조회한다.

SQS 전송이 성공한 뒤 DB 커밋에 실패하면 실제로 메시지가 도착했어도 예약이 미전송 상태로 남을 수 있다. 따라서 재전송에 대비해 AI 서버는 `event_id`로 중복 처리를 막아야 한다. `SENT`는 SQS 전달 완료이지 AI 설정 완료가 아니다. 이미 발송된 메시지는 이후 대결 취소로 회수되지 않으며, 실제 AI 대결 시작 차단도 유지한다.

## 계약

본문: event_id, event_type=competition_ai_setup, competition_id, agents.
각 agent: participant_id, account_id, risk_level, preferred_themes, excluded_themes, additional_request.
AI 2~4명을 설정 순서로 포함한다. risk_level은 1=안정형, 5=성장형이다. agent_id는 보내지 않는다.

## 설정과 운영 적용

application.yaml의 competition.ai-setup을 사용한다.

- AI_REQUEST_QUEUE_ENABLED: 기본 false. false여도 생성 예약은 저장한다.
- AI_REQUEST_QUEUE_URL: 개발/운영별 아래 URL 주입
- AWS_REGION: 기본 ap-northeast-2
- AI_SETUP_DISPATCH_ENABLED: 기본 true. 전송 일시 중지용
- AI_SETUP_DISPATCH_INTERVAL_MS: 기본 60000
- AI_SETUP_DISPATCH_BATCH_SIZE: 기본 20 (1~100)

개발: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-ai-request
운영: https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-prod-ai-request

기존 AWS 기본 인증 체인을 사용한다. BE에는 SendMessage 권한이 필요하다. AI의 수신·삭제 권한과 DLQ 설정은 인프라/AI 담당자가 확인한다. BE 발송 실패는 아웃박스에서, AI 수신 후 반복 실패는 SQS/DLQ에서 관리한다.

배포 전 db/competition-ai-setup-outbox.sql을 수동 적용해야 한다. 기존 리포트 테이블은 바꾸지 않는다. 이 작업에서 원격 DB에 SQL을 적용하지 않는다. 기존 대결은 소급 전송하지 않는다.

개발 연동은 AI 담당자 수신 준비와 권한 확인 후 전송을 활성화하고 생성→예약→SQS 수신을 확인한다. 운영 전송을 로컬에서 실행하지 않는다. 전송을 끄면 예약이 누적되므로 재활성화 전에 유효 대결을 확인한다.

## 범위

BE만 수정한다. FE/AI 코드는 수정하지 않는다. 설정 완료 통지, 실제 시작 활성화, 다수 AI 리포트는 별도 작업이다. 실제 AWS 연동과 운영 DB 적용은 아직 수행하지 않았다.

## 검증 기록 (2026.11.07)

- 새 메시지·SQS 발송·재시도·취소·아웃박스 저장과 통합 생성·기존 HTTP 시나리오: 22개 통과, 실패·오류·건너뜀 0개.
- SpotBugs 및 git diff --check 통과.
- 검증 중 겹친 Gradle 실행에서 EOF 오류가 발생해 종료 후 cleanTest로 정리하고 단일 실행으로 위 결과를 확인했다.
- 실제 SQS와 원격 MySQL 적용은 실행하지 않았다.

- 최종 전체 테스트: 744개, 실패 0, 오류 0, 건너뜀 24. 단일 Gradle 실행 BUILD SUCCESSFUL 확인.
