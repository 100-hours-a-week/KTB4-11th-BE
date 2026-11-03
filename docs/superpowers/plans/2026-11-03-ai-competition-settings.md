# AI 간 대결 생성 및 에이전트 일괄 설정 Implementation Plan

> **대체됨 (2026-11-03):** 사용자 결정으로 생성·설정을 통합한다. 이후 작업은 [통합 생성 계획](2026-11-03-ai-competition-unified-creation.md)을 따른다. 이 문서는 기존 분리 설계 이력으로만 보존한다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대결 조건 생성과 N개 AI 전략 일괄 등록을 별도 API로 제공하고, 설정이 완료된 대결만 실행한다.

**Architecture:** 기존 사용자 대 AI 경로는 유지하고 AI 간 대결의 생성·설정 처리를 분리한다. 참가 계좌, 참가자, 전략을 연결하며 에이전트별 화면 입력은 FE에 모았다가 한 번 전송한다. 아직 명세가 정의하지 않은 연결·생명주기 계약은 1단계에서 확정한 후 구현한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring MVC/Validation/Security, JPA, MySQL, JUnit/MockMvc/H2.

**Spec:** https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152#gid=2138787152 — 2026-11-03 조회. 아래에 해당 계약을 요약한다.

## Global Constraints

- 명세 경로는 `POST /api/v3/users/me/competitions/ai-settings`, 성공 상태는 **200**이다. 이전 대화에서 제안했던 `/.../{competition_id}/agents`, 201을 적용하지 않는다.
- 요청은 `agents` 배열이며 각 항목은 `risk_level`, `preferred_themes`, `excluded_themes`, `additional_request`다. `theme`를 `sector`로 바꾸지 않는다.
- 성공 응답은 `competition_id`, `agents: [{agent_id, participant_id}]`다. 명세에 없는 message는 추가하지 않는다.
- N명 설정을 한 번 호출하여 등록한다. 부분 성공은 허용하지 않는 안을 제안한다.
- 명세의 현재 생성 API는 `POST /api/v2/users/me/competitions`, user_vs_ai 예시 및 201 응답이다. AI 간 조건 생성 계약은 명세 보완이 필요하다.
- 코드 구현·DB 적용·브랜치 전환은 이 계획 작성에 포함하지 않는다.

## 확인한 기준 상태

- 현재 checkout: `feat/227-competition/report-sqs-request`. 작업 트리는 확인 시 깨끗했다.
- 로컬 및 원격 추적 브랜치에 `feat/217-competition/create-competition/ai-vs-ai`가 존재한다.
- 해당 브랜치의 `9741aad` 실제 변경은 `CompetitionCreateRequest.aiParticipantCount`에 `@Max(4)` 한 줄 추가다. 커밋 제목과 달리 AI 간 생성 구현은 없다.
- 현재 생성 Service는 user_vs_ai / AI 1명만 허용하며 장중 즉시 시작한다. 시작 스케줄러도 READY를 그대로 시작한다.
- 상세·결과·일별 기록·정산·취소는 사용자 1명 + AI 1명을 전제한다. 생성만 확장하여 운영하면 후속 처리에서 실패한다.

## Review Focus

1. 여러 미완성 대결 중 잘못된 대결에 설정 연결: 대상 ID와 소유권을 명시 검증한다(1·4단계).
2. 설정 중 스케줄러 실행: 참가자와 설정 완료 전 시작 및 AI 운용 대상 노출을 막는다(3·4단계).
3. 더블 클릭·통신 재시도: 중복 참가자/계좌/설정을 만들지 않는다(4단계).
4. 마지막 설정 저장 실패: 앞선 N-1개 저장과 상태 전환도 롤백한다(4단계).
5. 기존 사용자 대 AI 데이터: 생성·조회·정산 동작과 기존 스키마 데이터를 보존한다(2·5단계).

## 1단계: 연결 방식과 정책 확정

**산출물:** 요청/응답·오류·시작 시점이 명확한 API 명세. 이 단계 확정 전 아래 구현은 시작하지 않는다.

- [ ] 명세의 ai-settings 요청에는 대결 ID가 없다. **권장안: 경로는 명세대로 유지하고 `?competition_id={id}`를 필수로 추가**하여 본문은 agents만 유지한다. 사용자별 최신 미완성 대결을 추정해서 연결하지 않는다. 이 변경은 사용자·FE와 확정한다.
- [ ] AI 간 대결 조건 생성 경로와 `competition_type=ai_vs_ai` 지원을 확정한다. 기존 v2 생성 API의 유형 확장을 우선안으로 두되 명세의 user_vs_ai 전용 설명을 갱신한다.
- [ ] AI 인원 범위를 확정한다. DTO의 1~4 선언과 화면의 2·3 선택/4 잠금은 다르므로 4명 허용을 코드에서 추정하지 않는다.
- [ ] risk_level 다섯 값의 정확한 숫자·성향 대응, 필수/기본값, 테마 목록 출처·최대 수·중복·관심/제외 충돌, 추가 요청 길이와 null/빈 값 규칙을 확정한다.
- [ ] 권장 생명주기: 조건 생성 시 설정 미완료로 보관하고 계좌·참가자는 아직 만들지 않는다. 일괄 설정 성공 시 N개 계좌·참가자·전략을 함께 생성하고, 장중이면 시작/장외면 시작 대기로 전환한다. 중간 이탈 데이터의 표시·취소·보존 정책을 확정한다.
- [ ] 초기금은 각 AI에게 동일 지급하는지 확인한다. 응답 배열은 요청 순서와 동일하게 정의한다.
- [ ] 재요청은 중복 생성 없이 409로 거절하는 안을 확정한다. 동일 요청 성공 재응답이 필요하면 기존 멱등성 체계와 통합하는 계약을 별도로 정의한다.
- [ ] AI 간 목록 대표 수익률, 여러 AI의 승자·공동 1위, 결과와 리포트의 참가자별 구조를 확정한다. 현재 USER/AI/DRAW는 다수 AI 승자를 식별하지 못한다.

## 2단계: 저장 구조와 마이그레이션

**Files:** 수정 `competition/entity/Competition.java`; 신규 `competition/entity/CompetitionAgent.java`, `competition/repository/CompetitionAgentRepository.java`, `db/competition-ai-settings.sql`; 테스트 `competition/CompetitionAgentPersistenceTests.java`. Java 경로는 모두 `src/main/java/com/stock_spoon/river_be/` 기준이다.

**Interfaces:** CompetitionAgent는 참가자 1개에 유일하게 연결되고 설정과 입력 순서를 저장한다. 생성자와 저장 필드 제약은 1단계의 확정값을 사용한다.

- [ ] 참가자 연결 유일성·테마 왕복 저장·순서 보존·기존 Competition 복원 테스트를 먼저 작성하고 실패를 확인한다.
- [ ] AI_VS_AI 유형, 설정 완료를 표현하는 상태/필드, 전략 엔티티를 구현한다. 준비 상태 변경은 사용자 대 AI 기존 시작 정책을 보존한다.
- [ ] 운영 DB의 실제 컬럼 타입을 확인하여 ALTER·새 테이블·FK·유일 제약·기존 행 기본값을 작성한다. SQL 파일 작성과 실제 적용은 분리한다.
- [ ] `./gradlew.bat test --tests '*CompetitionAgentPersistenceTests'` PASS 확인 후 이 단계 변경만 커밋한다.

## 3단계: AI 간 대결 조건 생성과 시작 차단

**Files:** `competition/service/CompetitionService.java`, `competition/dto/CompetitionCreateRequest.java`, `competition/service/CompetitionStartScheduler.java`, `competition/repository/CompetitionRepository.java`; 테스트 `competition/CompetitionCreationTests.java`, 신규 `competition/CompetitionAiCreationTests.java`.

**Interfaces:** 기존 `createCompetition(Long userId, CompetitionCreateRequest request)`를 유지하며 유형별 생성으로 분기한다. 설정 미완료 AI 대결은 스케줄러 및 운용 대상에서 제외한다.

- [ ] AI 간 조건 생성 후 참가자/운용 계좌 없음, 장중에도 미시작, 스케줄러 미시작, 사용자 대 AI 기존 생성 유지 테스트를 먼저 작성하고 실패 확인한다.
- [ ] 공통 사용자 잠금·이름·기간·초기금 검증을 재사용하고 유형별 인원 정책을 적용한다. 계좌가 없는 미완성 대결끼리의 이름 중복도 처리한다.
- [ ] `./gradlew.bat test --tests '*CompetitionCreationTests' --tests '*CompetitionAiCreationTests'` PASS 후 커밋한다.

## 4단계: 에이전트 일괄 설정 API

**Files:** 신규 `competition/controller/CompetitionAiSettingsController.java`, `competition/dto/CompetitionAiSettingsRequest.java`, `competition/dto/CompetitionAiSettingsResponse.java`, `competition/service/CompetitionAiSettingsService.java`; 기존 CompetitionRepository·AccountRepository·CompetitionParticipantRepository; 신규 테스트 `competition/CompetitionAiSettingsTests.java`.

**Interfaces:** `CompetitionAiSettingsService.create(Long userId, Long competitionId, CompetitionAiSettingsRequest request)` → `CompetitionAiSettingsResponse`. Controller의 대상 ID 전달 방식은 1단계 확정 계약을 따른다.

- [ ] MockMvc로 200 및 응답 ID 연결/순서, 미인증·CSRF·타인/없는 대결, 잘못된 유형/상태, null 항목·배열·개수 불일치와 1단계 입력 경계를 테스트하고 실패 확인한다.
- [ ] JWT 사용자 ID를 전달하고 대결을 잠금 조회하여 소유권·상태·기등록 여부·설정 개수를 확인한다. N개 전략/계좌/참가자를 단일 트랜잭션에 저장한다. 모든 계좌는 aiManaged=true이고 응답은 실제 발급 ID를 사용한다.
- [ ] 설정 완료 후에만 시작 가능하게 한다. 마지막 저장 실패 시 전체 롤백, 동시 등록 2건 중 중복 생성 없음, 취소/시작과의 경합 테스트를 추가한다.
- [ ] 반복 요청 409 정책이면 FE는 응답 유실 시 상태 조회로 확인하도록 명세에 명시한다. 새 DB 쓰기 API의 기존 replay 정책 적용 여부는 config/DbWriteReplayBusinessCommand 및 관련 aspect/소비자 계약을 확인한 후 결정한다.
- [ ] `./gradlew.bat test --tests '*CompetitionAiSettingsTests' --tests '*CompetitionAiCreationTests'` PASS 후 커밋한다.

## 5단계: 운용·조회·종료 호환성 확보

**Files:** CompetitionDailyPerformanceService, CompetitionCompletionService, CompetitionCancellationService, CompetitionService, CompetitionHistoryAccess, CompetitionReportDispatchService 및 관련 DTO/Repository; `account/repository/AccountRepository.java`, AI 서버 snapshot 계층. 모두 기존 파일의 책임을 유지한다.

**Interfaces:** 1단계에서 확정한 다수 참가자 조회/결과 계약, 참가자 ID와 전략의 연결을 사용한다.

- [ ] 기존 사용자/AI 한 쌍 검사를 유형별 참가자 검사로 바꾸는 테스트를 먼저 작성한다. AI 간 N명 일별 기록·최종 순위·동률·전체 계좌 취소와 대기 주문 취소를 확인한다.
- [ ] 기존 결과의 단일 AI 필드를 다수 AI에 임의로 재사용하지 않는다. 합의한 응답 계약으로 확장하며 기존 user_vs_ai 응답을 회귀 검증한다.
- [ ] AI 서버가 각 계좌에 해당하는 전략을 받는 전달 계약을 확인하고 통합한다. 설정 저장만으로 AI가 전략을 적용한다고 간주하지 않는다.
- [ ] 기존 AI 거래 공개 제한과 다수 AI 공개 정책, 리포트의 여러 AI 처리도 확정 계약에 맞춘다.
- [ ] `./gradlew.bat test --tests '*Competition*' --tests '*AiUserSnapshot*'` PASS 후 커밋한다. 연동 완료 전 AI_VS_AI 운영 시작은 허용하지 않는다.

## 6단계: 통합 검증 및 문서 인계

**Files:** `docs/competition-creation.md`, 이 계획, 명세 시트의 해당 API 항목.

- [ ] 조건 생성 → N개 설정 일괄 저장 → 운용 대상 노출 → 일별 기록 → 종료/취소 흐름을 검증한다. 계정 두 개·대결 두 개로 대상 혼동이 없음을 확인한다.
- [ ] `./gradlew.bat test` 및 `./gradlew.bat spotbugsMain`을 실행하고 결과를 기록한다. 외부 서비스·환경 실패는 기능 성공으로 간주하지 않는다.
- [ ] MySQL 마이그레이션을 분리된 검증 DB에서 확인하고, 실제 운영 적용 절차·구버전 호환·반영 순서를 기록한다.
- [ ] 계약·호출 흐름·오류·FE 순서와 응답 유실 처리 문서를 갱신하고 구현 범위/후속 범위를 구분한다.

## 작업 브랜치와 완료 범위

- 실행 시 최신 dev와 217번 브랜치의 차이·병합 상태를 다시 확인한다. 오래된 217번 브랜치에서 바로 시작하지 말고 최신 기반을 맞춘 후 재사용 여부를 결정한다.
- 2~4단계는 생성·설정 기능의 구현 단위다. 5단계가 끝나기 전에는 실제 AI 간 대결 운용 완료라고 선언하지 않는다.
- 이 문서는 현재 단계별 계획이다. 1단계 정책 미확정값을 임의 상수나 상태로 구현하지 않는다.
