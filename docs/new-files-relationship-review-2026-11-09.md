# 신규 파일 역할·연결부 점검

검토 기준: `chore/193-cloudwatch-error-logging`, HEAD `574a23d`. 병합 직전 로깅 브랜치 `94ff2f2`와 병합한 dev `8f5c5a3`를 비교해 신규 Java 파일 43개를 확정했다. 현재 HEAD의 해당 파일과 호출자·의존 코드·관련 테스트/DDL을 읽었다. 기존 파일은 연결 근거 확인에 사용했으며 전체 저장소 감사는 아니다. 여러 API 요청 및 스케줄러 동시 실행을 전제해 잠금·커밋·외부 호출의 경계를 확인했다. 실제 운영 프로세스 수는 확인하지 않았다.

## 발견사항

### 1. [P1] AI 설정 Outbox가 있는 회원의 탈퇴가 FK 오류로 롤백된다

`UserDeletionRepository.java:42`는 대결을 삭제하기 전에 `competition_report_outbox`는 지우지만 `competition_ai_setup_outbox`는 지우지 않는다. 신규 `CompetitionAiSetupOutbox`가 대결을 참조하고, `db/competition-ai-setup-outbox.sql:11`에도 cascade 삭제 없는 FK가 있다. AI 간 대결 생성은 큐 발송 활성 여부와 무관하게 설정 Outbox를 예약하므로 PENDING/SENT/CANCELLED 어느 상태든 행이 남아 있으면 대결 삭제가 막힌다. 상위 `UserWithdrawalService`는 DB 삭제보다 Kakao unlink를 먼저 실행하므로 DB는 롤백되어도 외부 연결 해제는 이미 끝났을 수 있다.

최소 수정은 대결 삭제 전에 해당 회원 대결의 설정 Outbox를 삭제하는 것이다. 기존 `UserWithdrawalTests`는 리포트 Outbox만 생성하고 설정 Outbox를 생성하지 않으며, 테이블 검증 목록에도 설정 Outbox가 없다. 이 사례를 fixture와 삭제 검증에 추가해야 한다. 이번 점검에서는 소스·DDL로 확인했고 실행 재현 테스트는 추가하지 않았다.

### 2. [P2] AI 설정 예약 실패가 전략 저장 실패 단계로 기록될 수 있다

`CompetitionAgentCreationService.java:46`의 마지막 단계가 `ai_strategy_save`이고 메서드 종료 시 계좌 ID만 비운다. 이어 `CompetitionService.java:303`이 `reserve()`를 호출하지만 `CompetitionAiSetupReservationService`는 CreationLog 단계를 갱신하지 않는다. 전략 조회·개수 검사·JSON 직렬화·Outbox 저장 중 실패하면 Controller의 생성 실패 로그는 실제 예약 단계 대신 `ai_strategy_save`를 기록한다. 따라서 앞선 로깅 보완만으로 생성 연결부가 모두 구분된 것은 아니다.

최소 보완은 예약 서비스에서 전략 조회/검증, 메시지 직렬화, Outbox 저장 직전에 기존 CreationLog stage를 갱신하는 것이다. `CompetitionUnifiedCreationTests.reservationFailureRollsBackCompetitionAccountsAndStrategies`는 예약 실패의 데이터 롤백은 검증하지만 실패 stage는 검증하지 않는다. 오류 주입 후 출력되는 stage를 검사하는 사례가 필요하다.

## 흐름과 경계

- AI 간 대결 생성: Controller → CompetitionService 트랜잭션 → AgentCreationService(MANDATORY) → 계좌·참가자·전략 저장 → AiSetupReservationService(MANDATORY) → 동결 메시지·Outbox 저장 → 전체 커밋. 중간 로그는 커밋 성공 증거가 아니다.
- AI 설정 전송: Scheduler → DispatchService 프록시 → 대결/Outbox 잠금 → Publisher → SQS 수락 → SENT 저장 → 커밋 → Scheduler의 outbox_sent. SENT는 AI 처리 완료가 아니다. SQS와 DB는 하나의 트랜잭션이 아니므로 수락 뒤 DB 실패 시 동일 eventId 재전송 가능.
- 리포트 요청: 정산 트랜잭션 → AI 참가자별 ReportOutbox → Scheduler → DispatchService → 그 참가 계좌의 체결 reasonId 조회 → Publisher → SQS → SENT 커밋. AI 설정과 달리 전체 payload가 예약 시 동결되지 않는다. 실패한 AI의 Outbox만 다음 주기에 재시도된다.
- 리포트 수신: SecurityConfig의 사용자 ID subject+AI actor 검사 → SaveRequest 검증 → ReportService 저장 트랜잭션 → 해당 참가자 본문만 교체·ready 갱신 → 커밋 → Controller 성공 로그. ai-server 전용 subject는 이 API의 주체가 아니다.
- 상세 조회: 인증·읽기 재시도 → DB 읽기 스냅샷 → 트랜잭션 밖 Redis 평가·시세 HTTP 종목명 조회 → 참가자별 응답. 상세 자산은 현재 평가, 결과 순위는 확정 자산이며 표시 반올림과 순위 계산을 구분한다.
- 취소: 대결→계좌 잠금 → 참가자 전체 주문 취소·비활성화 커밋 → 외부 예약 정리. AI 설정 발송도 대결→Outbox 잠금이므로 취소/발송 순서를 DB에서 직렬화한다. 이미 SQS 수락된 메시지를 취소가 회수하지는 않는다.
- 인증·DB 복구: 존재 검사는 필터에서 독립 읽기 트랜잭션, 읽기 재시도는 Controller 경계, 쓰기 캡처는 요청 의미 보관이다. 세 경로를 하나의 재시도로 해석하면 안 된다. 실제 쓰기 fallback 발송은 기존 Resolver가 담당한다.

## 파일별 역할과 관계 (43개)

| 파일 | 역할 | 호출·의존 관계 및 주의점 |
|---|---|---|
| [AccountDeletionService](..\src\main\java\com\stock_spoon\river_be\account\service\AccountDeletionService.java) | 계좌 소프트 삭제 | AccountController → 계좌 잠금·대기 주문 취소·비활성화 커밋 → 예약 정리·구독 갱신. 대결 계좌 개별 삭제는 거절한다. |
| [JwtUserExistenceVerifier](..\src\main\java\com\stock_spoon\river_be\auth\service\JwtUserExistenceVerifier.java) | JWT 회원 존재 확인 | 인증 필터 → REQUIRES_NEW 읽기 트랜잭션. 연결 장애만 제한 재시도, 회원 부재와 DB 장애를 구분한다. |
| [CompetitionSchedulingConfiguration](..\src\main\java\com\stock_spoon\river_be\competition\config\CompetitionSchedulingConfiguration.java) | 정산 전용 실행기 | 완료 스케줄러가 참조하는 단일 스레드 Bean. 종가 조회·성과 복구를 주문 스케줄러와 분리한다. |
| [CompetitionNameAvailabilityController](..\src\main\java\com\stock_spoon\river_be\competition\controller\CompetitionNameAvailabilityController.java) | 대결명 확인 API | GET v3 → DbReadRetryAspect → CompetitionService.checkNameAvailability. 생성 예약이 아니라 현재 사용 가능 여부만 반환한다. |
| [CompetitionReportSaveController](..\src\main\java\com\stock_spoon\river_be\competition\controller\CompetitionReportSaveController.java) | AI 리포트 수신 API | SecurityConfig의 숫자 subject·actor=AI 검사 → DTO 검증 → CompetitionReportService.save → 커밋 후 성공 로그. |
| [CompetitionAgentRequest](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionAgentRequest.java) | AI 설정 입력 | CompetitionCreateRequest의 중첩 agents → 생성 서비스. 범위·필수값은 DTO, 테마 중복·충돌은 생성 서비스가 검사한다. |
| [CompetitionCancelResponse](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionCancelResponse.java) | 취소 응답 | CompetitionCancellationService → CompetitionController. competition_id와 state를 직렬화한다. |
| [CompetitionNameAvailabilityResponse](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionNameAvailabilityResponse.java) | 이름 확인 응답 | CompetitionService → 이름 확인 Controller. available 값만 반환한다. |
| [CompetitionReportSaveRequest](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionReportSaveRequest.java) | 참가자별 리포트 입력 | AI 수신 Controller → 저장 서비스. participant_id 및 news/thoughts 배열과 각 항목을 검증한다. 빈 배열은 허용한다. |
| [CompetitionReportSaveResponse](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionReportSaveResponse.java) | 리포트 저장 응답 | 저장 Controller가 트랜잭션 반환 후 생성한다. 참가자 ID는 요청으로 대상 지정, 응답은 대결 ID와 메시지다. |
| [CompetitionReturnPercent](..\src\main\java\com\stock_spoon\river_be\competition\dto\CompetitionReturnPercent.java) | 표시 수익률 계산 | 상세·결과 서비스가 호출한다. 응답만 소수점 1자리 HALF_UP, 확정 순위 비교에는 사용하지 않는다. |
| [CompetitionAgent](..\src\main\java\com\stock_spoon\river_be\competition\entity\CompetitionAgent.java) | 참가자 전략 저장 모델 | 생성 서비스가 참가자와 1:1 연결하고 position과 테마 순서를 저장한다. 예약·상세·결과·성과·정산·리포트가 참조한다. |
| [CompetitionAiSetupOutbox](..\src\main\java\com\stock_spoon\river_be\competition\entity\CompetitionAiSetupOutbox.java) | AI 설정 발송 기록 | 대결당 1행, eventId 고정, 메시지 본문 동결. PENDING → SENT 또는 CANCELLED. SENT는 AI 설정 완료가 아닌 전송 완료다. |
| [CompetitionReportOutbox](..\src\main\java\com\stock_spoon\river_be\competition\entity\CompetitionReportOutbox.java) | 리포트 발송 기록 | 완료 정산에서 AI 참가자당 1행. 대결·참가자 FK와 고정 requestId를 가진다. SENT는 리포트 저장 완료를 뜻하지 않는다. |
| [CompetitionAiSetupQueueConfiguration](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionAiSetupQueueConfiguration.java) | AI 설정 SQS 구성 | 조건부 전용 실행기·SqsAsyncClient·Publisher Bean. 리전/Standard Queue URL·타임아웃·기본 자격증명 체인을 설정한다. |
| [CompetitionAiSetupRequest](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionAiSetupRequest.java) | AI 설정 메시지 계약 | 예약 서비스가 event_id·competition_id·agents를 직렬화한다. 각 에이전트에 participant_id와 account_id가 포함된다. |
| [CompetitionAiSetupRequestPublisher](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionAiSetupRequestPublisher.java) | 설정 메시지 SQS 발송 | DispatchService → 저장된 본문 크기 검사 → SQS 응답 대기·messageId 확인. 인터럽트 복원, 실패는 호출자로 전파한다. |
| [CompetitionReportQueueConfiguration](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionReportQueueConfiguration.java) | 리포트 SQS 구성 | 조건부 전용 실행기·클라이언트·Publisher Bean. AI 설정 큐와 별도로 구성된다. |
| [CompetitionReportRequest](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionReportRequest.java) | 참가자 리포트 요청 계약 | DispatchService가 대결/참가자 ID와 그 계좌의 reason UUID 목록을 전달한다. 빈 목록 허용, null·잘못된 UUID 거절. |
| [CompetitionReportRequestPublisher](..\src\main\java\com\stock_spoon\river_be\competition\messaging\CompetitionReportRequestPublisher.java) | 리포트 요청 SQS 발송 | JSON 인코딩 → 크기 검사 → eventId 메시지 속성 → SQS 수락 로그. 반환 messageId는 큐 수락 근거이며 DB 커밋과 별도다. |
| [CompetitionAgentRepository](..\src\main\java\com\stock_spoon\river_be\competition\repository\CompetitionAgentRepository.java) | 전략 조회·저장 | 생성·예약·상세·결과·성과·정산·리포트에서 참가자의 대결 ID로 position 순 조회한다. |
| [CompetitionAiSetupOutboxRepository](..\src\main\java\com\stock_spoon\river_be\competition\repository\CompetitionAiSetupOutboxRepository.java) | 설정 발송 후보·잠금 조회 | 예약 서비스가 저장, 스케줄러가 상한 ID와 페이지 후보 조회, 발송 서비스가 비관적 쓰기 잠금으로 재확인한다. |
| [CompetitionReportOutboxRepository](..\src\main\java\com\stock_spoon\river_be\competition\repository\CompetitionReportOutboxRepository.java) | 리포트 발송 후보·근거 조회 | 스케줄러 후보 조회·발송 잠금·참가 계좌의 체결 AI reasonId 조회. left join으로 누락된 근거도 집계할 수 있다. |
| [CompetitionAgentCreationService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionAgentCreationService.java) | AI 계좌·참가자·전략 생성 | CompetitionService의 기존 트랜잭션에 MANDATORY로 참여한다. 2~4개 설정을 순회하고 CreationLog 단계와 계좌 ID를 갱신한다. |
| [CompetitionAiSetupDispatchScheduler](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionAiSetupDispatchScheduler.java) | 설정 발송 반복 처리 | 전용 실행기 → 후보 상한·ID 페이지 → 서비스 프록시. 각 커밋 반환 뒤 outbox_sent 기록, 실패 행을 넘어 다음 후보로 진행한다. |
| [CompetitionAiSetupDispatchService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionAiSetupDispatchService.java) | 설정 1건 전송·상태 변경 | 대결 → Outbox 순 잠금, 취소/이미 처리 여부 재확인. 동결 본문 발송 후 SENT flush. 실패 시 롤백, 다음 주기 재시도. |
| [CompetitionAiSetupReservationService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionAiSetupReservationService.java) | 설정 발송 예약 | AI 생성 서비스 뒤 같은 생성 트랜잭션에 참여한다. 전략 목록을 메시지로 동결하고 Outbox 저장. 실패 시 생성 전체 롤백. |
| [CompetitionCancellationService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionCancellationService.java) | 대결 취소 | Controller → 대결 → 계좌 ID 순 잠금. 참가자 검증·대기 주문 취소·전체 계좌 비활성화를 커밋한 후 예약 정리·구독 갱신. |
| [CompetitionDetailService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionDetailService.java) | 상세 응답 조립 | Controller → CompetitionService → 읽기 트랜잭션에서 스냅샷 복사 → 트랜잭션 밖 Redis 현재가·HTTP 종목명 조회. AI position 순 참가자 배열 구성. |
| [CompetitionParticipants](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionParticipants.java) | 참가 구성·순위 공통 규칙 | 결과·리포트·취소·성과·정산에서 호출한다. AI 2~4명/사용자+AI 구분, 계좌 소유권·전략 position 검증, 반올림 전 확정 자산비율 비교. |
| [CompetitionReportDispatchScheduler](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionReportDispatchScheduler.java) | 리포트 발송 반복 처리 | 전용 실행기 → 후보 페이지 → 서비스 프록시 → 커밋 반환 후 sent 로그. 실행 중 신규 후보는 다음 주기에 처리한다. |
| [CompetitionReportDispatchService](..\src\main\java\com\stock_spoon\river_be\competition\service\CompetitionReportDispatchService.java) | 참가자별 리포트 발송 | Outbox 잠금 → 완료 상태·AI 계좌 소유권 확인 → 해당 계좌 reasonId 조회·누락 집계·중복 제거 → SQS → SENT flush. 실패 시 PENDING 유지. |
| [DbReadRetryAspect](..\src\main\java\com\stock_spoon\river_be\config\DbReadRetryAspect.java) | 읽기 API 재시도 | DbReadRetryable Controller 바깥에서 실행, 매 시도 서비스 트랜잭션을 새로 시작한다. 연결 오류만 재시도하며 일반 업무 오류는 전파한다. |
| [DbReadRetryable](..\src\main\java\com\stock_spoon\river_be\config\DbReadRetryable.java) | 읽기 재시도 표시 | 읽기 Controller 메서드와 Aspect를 연결하는 런타임 Annotation. 자체 로그·실행 로직이 없다. |
| [DbWriteReplayBusinessCommand](..\src\main\java\com\stock_spoon\river_be\config\DbWriteReplayBusinessCommand.java) | 쓰기 명령 캡처 표시 | 계좌/주문/관심종목/대결 생성 Controller에 명령 유형을 지정한다. 자체적으로 재실행하지 않는다. |
| [DbWriteReplayBusinessCommandAspect](..\src\main\java\com\stock_spoon\river_be\config\DbWriteReplayBusinessCommandAspect.java) | 쓰기 요청의 의미 캡처 | Controller 인자 위치에서 JWT·대상·DTO를 복사·정규화·해시화 → RequestContext. DB fallback Resolver가 나중에 사용하며 캡처 실패는 원래 요청 결과를 대체하지 않는다. |
| [JwtUserExistenceAuthenticationFilter](..\src\main\java\com\stock_spoon\river_be\config\JwtUserExistenceAuthenticationFilter.java) | 탈퇴 사용자 인증 차단 | SecurityConfig가 Bearer 필터 뒤에 설치한다. ai-server 전용 주체는 별도 경로, 숫자 subject는 Verifier. 부재 401/DB 확인 실패 503, 성공만 Controller로 진행. |
| [OpenApiConfig](..\src\main\java\com\stock_spoon\river_be\config\OpenApiConfig.java) | Swagger 보안·오류 문서 | OpenAPI 및 OperationCustomizer Bean. 쿠키 인증과 인증/CSRF 오류 응답을 문서화하며 런타임 인증을 수행하지 않는다. |
| [SwaggerCsrfTransformer](..\src\main\java\com\stock_spoon\river_be\config\SwaggerCsrfTransformer.java) | Swagger 변경 요청 CSRF 연결 | Swagger UI 페이지 변환 시 classpath JS를 삽입한다. 마스킹된 CSRF 토큰을 발급받는 interceptor이며 리소스 실패는 페이지 생성 오류로 전파된다. |
| [MarketHttpConfig](..\src\main\java\com\stock_spoon\river_be\market\MarketHttpConfig.java) | 시세 HTTP 클라이언트 구성 | URL 검증·연결/읽기 타임아웃·리다이렉트 금지 → MarketStockClient Bean → KiwoomConfig에서 운영 시장 클라이언트에 주입한다. |
| [MarketStockClient](..\src\main\java\com\stock_spoon\river_be\market\MarketStockClient.java) | 시세 서버 계약 변환 | KiwoomMarketClient의 종목정보/종가 연결부. stocks 응답을 기존 내부 필드로 변환하고 bars를 UTC 거래일 기준 탐색한다. 운영 현재가는 별도 Redis 경로다. |
| [UserDeletionRepository](..\src\main\java\com\stock_spoon\river_be\user\repository\UserDeletionRepository.java) | 탈퇴 연관 데이터 일괄 삭제 | UserWithdrawalService 트랜잭션 안에서 FK 자식부터 native SQL 삭제 → clear. AI 설정 Outbox 누락은 발견사항 1 참고. |
| [UserWithdrawalService](..\src\main\java\com\stock_spoon\river_be\user\service\UserWithdrawalService.java) | 탈퇴 외부 호출·삭제 조정 | UserController → 회원·대결·계좌 잠금 → Kakao unlink → DB 전체 삭제 → 커밋 후 Redis 예약 정리·구독 갱신. 외부 unlink는 DB 롤백으로 복구되지 않는다. |

## 로깅 검토 결론

DTO·Entity·Repository마다 로그를 추가하기보다, 요청/트랜잭션/외부 호출을 조정하는 서비스와 실행 경계에서 기록하는 구성이 맞다. 현재 상세·결과·리포트 저장 및 시세 HTTP 보완은 이 경계와 맞는다. 발견사항 2의 생성→예약 경계는 아래 후속 작업에서 보완했다. 설정 Publisher는 messageId를 검사하지만 호출자에게 반환하거나 기록하지 않아 SQS messageId 기반 조사는 현재 어렵다. 이는 업무 오류가 아닌 추가 관측 항목이다.

## 확인 수준

정적 코드·호출자·관련 테스트 소스·수동 DDL을 대조했다. 설정/리포트 생성·전송·실패·상세 응답·인증 재시도 등의 기존 테스트를 확인했다. 설정 전송 테스트는 mock 기반이며 리포트 전송 테스트처럼 실제 트랜잭션의 SENT 저장 실패 롤백·동시 전송을 직접 검증하는 통합 사례는 확인하지 못했다. 이를 통과한 것으로 해석하지 않는다.

이번 점검은 테스트를 새로 실행하지 않았으며 이전 759개 회귀 테스트 통과는 신규 발견 시나리오가 검증됐다는 근거가 아니다. 운영 DB 스키마, AWS SQS/AI 수신 측 중복 처리, Loki 수집은 확인하지 않았다. 애플리케이션 코드·커밋·원격은 변경하지 않았다. 이 보고서만 추가했다.

현재 상태: 발견사항 2의 로깅 보완은 완료했다. 발견사항 1은 별도 업무 로직 수정으로 남아 있다.


## 후속 작업: 발견사항 2 수정

사용자 요청으로 `CompetitionAiSetupReservationService`에 전략 조회·검증, 메시지 직렬화, Outbox 저장 단계를 추가했다. `CompetitionAiSetupReservationLoggingTests`의 4개 오류 주입 사례가 수정 전 잘못된 ai_strategy_save로 실패하는 것을 확인했고, 수정 후 올바른 stage 및 민감 원문 미출력·예외 재전파·MDC 정리를 검사했다. 관련 생성·AI 설정 회귀 테스트와 SpotBugs가 통과했다. 발견사항 1(회원 탈퇴 Outbox 삭제 누락)은 이 수정에 포함하지 않았다.
