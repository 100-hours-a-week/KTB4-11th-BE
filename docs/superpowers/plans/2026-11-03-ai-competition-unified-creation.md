# 대결·AI 전략 통합 생성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자 대 AI와 AI 간 대결을 기존 생성 API에서 받고, AI 간 대결의 전략을 생성 시 한 번에 저장한다.

**Architecture:** CompetitionController → CompetitionService.createCompetition(userId, request)의 기존 진입점을 유지한다. 요청 유형에 따라 참가자 생성만 분기하며, AI 전략 저장은 생성 트랜잭션 안의 내부 서비스가 담당한다. 미완성 대결을 먼저 저장하는 별도 HTTP 흐름은 제거한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, MVC/Validation/Security, JPA, MySQL, JUnit/MockMvc/H2.

**Spec:** 사용자와 2026-11-03 확정한 이 대화의 통합 생성 계약. 기존 시트 https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152#gid=2138787152 의 별도 ai-settings 항목을 대체하는 제안이다. 시트 자체는 아직 수정하지 않았다.

## Global Constraints

- 이 계획은 `2026-11-03-ai-competition-settings.md`의 별도 생성·설정 계획을 대체한다. 기존 진행 기록은 이력으로 보존한다.
- 범위는 생성·전략 저장까지다. 다수 AI의 실제 운용·조회·정산은 후속이다. AI 간 대결은 READY 유지 및 자동 시작 제외를 유지한다. 사용자 대 AI의 기존 시작 정책은 보존한다.
- 요청: `POST /api/v2/users/me/competitions`. 인증·CSRF·JWT subject의 사용자 ID 처리와 기존 공통 필드를 유지한다.
- `competition_type=user_vs_ai`: AI 수는 1, `agents` 생략/null 허용. 실수로 보낸 설정을 버리지 않도록 non-null agents는 400으로 거절하는 방침이다.
- `competition_type=ai_vs_ai`: AI 수 2~4, agents 필수. null·빈 배열·null 항목 금지. 배열 개수는 ai_participant_count와 같아야 한다.
- 에이전트 필드: risk_level, preferred_themes, excluded_themes, additional_request. risk_level은 1=안정형, 2=안정추구형, 3=위험중립형, 4=성장추구형, 5=성장형.
- 기존 중간 구현의 입력 제한을 유지한다: 테마 배열 필수·빈 배열 허용, 테마당 255자 이하·공백 및 중복·관심/제외 교집합 거절, 추가 요청은 선택/null이면 빈 문자열·최대 4000자. 확정된 테마 카탈로그가 없어 임의 목록 검증은 하지 않는다.
- 초기금은 각 참가 계좌에 동일 지급한다. 입력 배열 순서를 저장한다.
- 성공 응답은 기존 201 및 `{"message":"success","competition_id":1,"state":"ready"}` 형식을 유지한다. 생성에 불필요한 별도 설정 응답은 제거한다.
- 저장 중 오류 시 대결·N개 계좌·N개 참가자·N개 전략 전체 롤백. 재시도/동시 생성은 기존 사용자 잠금과 이름 중복 정책으로 보호하며 두 번째 성공 대결을 만들지 않는다.

## Review Focus

1. user_vs_ai의 agents 생략/null과 ai_vs_ai의 누락을 같은 동작으로 처리하지 않는다(1단계).
2. 배열의 마지막 항목 검증/저장 실패에도 DB에 대결·앞선 계좌가 남지 않는다(2단계).
3. 생성 직후 장중 스케줄러가 운용 미지원 AI 대결을 시작하지 않는다(2단계).
4. 같은 사용자·이름으로 동시에 생성해 계좌와 대결이 중복 생성되지 않는다(2단계).
5. 배포 DB와 회원 탈퇴의 새 전략 FK/테마 삭제 순서가 일치한다(3단계).

## 변경 파일과 역할

Java 파일 경로는 `src/main/java/com/stock_spoon/river_be/` 기준이다.

| 파일 | 역할/조치 |
|---|---|
| competition/dto/CompetitionCreateRequest.java | nullable agents 필드와 중첩 검증 추가 |
| competition/dto/CompetitionAgentRequest.java (신규) | 기존 설정 DTO 안의 Agent record를 요청 항목으로 추출 |
| competition/service/CompetitionService.java | 공통 검증 → 유형별 참가자 생성 → 기존 응답. 루트 트랜잭션 소유 |
| competition/service/CompetitionAgentCreationService.java (신규) | 기존 전략 저장 로직을 내부 호출 전용으로 이동 |
| competition/entity/Competition.java | AI_VS_AI 유지, aiSettingsComplete 필드·완료 메서드 제거 |
| competition/entity/CompetitionAgent.java, repository/CompetitionAgentRepository.java | 전략·참가자 연결 및 순서 저장 구조 재사용 |
| competition/repository/CompetitionRepository.java | AI 대결 자동 시작 제외와 이름 중복 검사 유지 |
| account/service/AccountService.java | AI 대결명이 일반 계좌 생성/이름 변경과 충돌하지 않도록 기존 수정 유지 |
| user/repository/UserDeletionRepository.java | 테마 → 전략 → 참가자 삭제 유지 |
| db/competition-ai-settings.sql | AI 유형 및 전략 테이블 유지, 설정 완료 컬럼 추가 제거 |
| competition/dto/CompetitionAiSettingsRequest.java, CompetitionAiSettingsResponse.java | 독립 API의 DTO 제거 |
| competition/service/CompetitionAiSettingsService.java | 독립 등록 서비스 제거. 재사용 로직은 위 내부 서비스로 이동 |

기존 Controller를 사용하므로 별도 ai-settings Controller, competition_id 경로/쿼리, 별도 API 응답은 만들지 않는다.

## Task 1: 통합 요청과 유형별 검증

**Tests:** `src/test/java/com/stock_spoon/river_be/competition/CompetitionAiCreationTests.java`, `CompetitionAiSettingsRequestTests.java`를 통합 요청 테스트로 변경, 기존 CompetitionCreationTests 유지.

**Interfaces:** `CompetitionCreateRequest.getAgents(): List<CompetitionAgentRequest>`; `CompetitionAgentRequest(Integer riskLevel, List<String> preferredThemes, List<String> excludedThemes, String additionalRequest)`.

- [ ] user_vs_ai에서 agents 생략/null 성공, non-null 배열은 INVALID_REQUEST로 거절하는 테스트를 먼저 작성한다.
- [ ] ai_vs_ai에서 agents 누락/null/빈 배열/null 항목, 인원 1·5, 개수 불일치, 위험도 0·6, 테마 및 추가 요청 경계 실패 테스트를 작성한다.
- [ ] 테스트를 실행해 미구현 실패를 확인한다.
- [ ] nullable agents에 @Valid를 적용하되 필수 여부는 유형별로 검사한다. 공통 필드·에이전트 항목 검증은 HTTP와 직접 서비스 호출 모두 동일하게 적용한다. 누락/형식 오류는 INVALID_REQUEST, 유효한 범위 내 인원과 배열 개수 불일치는 AGENT_COUNT_MISMATCH로 정한다.
- [ ] `./gradlew.bat test --tests '*Competition*RequestTests' --tests '*CompetitionCreationTests' --tests '*CompetitionAiCreationTests'`로 검증한다. 저장 전환 중인 테스트는 Task 2와 같은 변경 단위에서 완료한다.

## Task 2: 생성 트랜잭션 안으로 전략 저장 통합

**Interfaces:** `CompetitionAgentCreationService.createParticipants(Competition competition, List<CompetitionAgentRequest> agents): void`. 이 메서드는 루트 생성 트랜잭션 안에서만 실행한다. HTTP 사용자/대결 ID 조회와 독립 응답 생성 책임을 갖지 않는다.

- [ ] AI 2·3·4명 생성 시 대결 1개, AI 계좌/참가자/전략 각 N개, 계좌별 동일 초기금, 전략·배열 순서 연결을 테스트한다.
- [ ] 마지막 참가자 저장 실패 시 대결까지 전부 롤백, 마지막 테마 오류도 DB 잔여 없음, 같은 이름의 동시 생성 1건만 성공하는 테스트를 먼저 작성하고 실패 확인한다.
- [ ] 기존 CompetitionService.createCompetition의 AI 조건만 저장하던 조기 반환을 제거한다. 공통 검증을 통과한 AI 대결에서 내부 서비스로 계좌·참가자·전략을 생성하고 기존 생성 응답을 반환한다.
- [ ] 내부 서비스에 기존 이름 생성·테마 정규화·전략 저장을 옮긴다. 별도 설정용 소유권 조회·중복 등록 상태·완료 상태 처리는 제거한다.
- [ ] aiSettingsComplete 필드/완료 메서드를 제거한다. 생성에 성공한 AI 대결은 항상 설정이 존재하므로 별도 완료 플래그를 두지 않는다.
- [ ] 시작 스케줄러와 Entity.start가 AI 간 대결을 시작하지 않는 회귀 테스트를 유지한다. 이번 작업의 성공을 실제 운용 가능으로 해석하지 않는다.
- [ ] 별도 설정 서비스·DTO와 전용 등록 테스트를 제거하고, 동시성·롤백·테마 검증 테스트는 통합 생성 테스트로 이전한다.
- [ ] `./gradlew.bat test --tests '*Competition*' --tests '*AccountControllerTests' --tests '*UserWithdrawalTests'`로 검증 후 구현 단위를 커밋한다. 이때 관련 파일만 스테이징한다.

## Task 3: 실제 HTTP 계약·DB·문서 마무리

**Tests:** 신규 `src/test/java/com/stock_spoon/river_be/competition/CompetitionUnifiedCreationControllerTests.java`, 기존 CompetitionAgentPersistenceTests 및 UserWithdrawalTests.

- [ ] 실제 보안 필터/MockMvc로 POST 생성 201·응답 필드, user_vs_ai 생략/null, ai_vs_ai 요청 및 오류 코드를 검증한다. 인증/CSRF 누락, 잘못된 JSON·중첩 필드, 타 사용자 ID를 본문으로 보내도 소유자가 JWT 사용자임을 확인한다.
- [ ] 마이그레이션 SQL에서 미적용 ai_settings_complete 컬럼 추가를 제거한다. 전략 테이블·참가자 유일성·외래키·회원 탈퇴 정리는 유지한다. 이미 외부 DB에 컬럼을 적용한 사람이 있다면 임의 DROP하지 않고 스키마를 별도 확인한다.
- [ ] 생성과 전략 저장의 단일 트랜잭션이 기존 DB 쓰기 감사/replay 정책에 맞는지 config/DbWriteReplayBusinessCommand(COMPETITION_CREATE) 경계에서 확인한다. 독립 명령 유형은 추가하지 않는다.
- [ ] docs/competition-creation.md와 신규 docs/competition-ai-creation.md에 통합 요청/201 응답, 오류, 화면별 FE 임시 보관과 마지막 1회 호출, 위험도 매핑, READY 유지 및 후속 운용 범위를 기록한다.
- [ ] `./gradlew.bat test spotbugsMain --console=plain` 및 `git diff --check`를 실행하고 테스트 개수·제외·실패를 기록한다. 이전 분리 서비스의 테스트 통과 결과를 통합 구현 완료 근거로 재사용하지 않는다.
- [ ] 격리된 MySQL 검증 환경이 있으면 SQL 적용·기존 데이터 유지·생성·탈퇴를 확인한다. 없으면 H2 검증과 MySQL 미실행을 명시한다. 운영 DB 적용·푸시·배포는 별도 작업이다.
- [ ] 최종 변경을 리뷰하고 발견된 기능/데이터 오류를 수정한 뒤 관련 검증을 다시 실행한다. API 시트 변경이 필요하면 확정 계약과 구현 결과를 대조해 반영한다.

## 진행 순서와 완료 기준

1. 현재 217번 브랜치의 미커밋 변경을 출발점으로 사용하며 다른 작업을 초기화하지 않는다.
2. Task 1~2는 연속된 계약·저장 변경으로 실행한다. 중간에 분리 API와 통합 API를 동시에 노출하지 않는다.
3. Task 3에서 HTTP 진입부터 저장까지 검증한 후 생성·설정 구현 완료를 보고한다.
4. 다수 AI 결과·정산·운용 시작은 이번 범위에 포함하지 않는다. 해당 기능 구현 전 AI 대결의 자동 시작 차단을 해제하지 않는다.

**현재 상태 (2026-11-03):** 통합 생성 구현 및 HTTP·회귀 검증 완료. 전체 테스트 674개(실패 0, 건너뜀 18), SpotBugs 및 별도 리뷰 통과. 실제 MySQL DDL 적용은 Docker 엔진 부재로 미실행이며 FE·API 시트 변경은 별도 작업이다. 자세한 실행 기록은 [진행 기록](2026-11-03-ai-competition-unified-creation-progress.md)을 따른다. 아래 체크리스트는 실행 전 작성한 절차를 보존한다.
