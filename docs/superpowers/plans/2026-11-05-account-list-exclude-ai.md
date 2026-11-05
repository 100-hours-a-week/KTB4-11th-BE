# 계좌 목록 AI 계좌 제외 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** GET /api/v1/users/me/accounts에서 본인의 활성 비AI 계좌만 반환한다.

**Architecture:** 목록 Repository 쿼리에서 aiManaged=false를 추가해 자산 평가 전에 AI 계좌를 제외한다. 일반 AI와 대결 AI 모두 제외하고 대결 사용자 계좌는 유지한다.

**Tech Stack:** Java 21, Spring Data JPA, MockMvc/JUnit, Gradle.

**Spec:** 2026-11-05 사용자 요청: 비활성 계좌 외에 AI 계좌도 목록 응답에서 제외.

## Global Constraints

- 회원 ID 일치, active=true, aiManaged=false를 모두 만족해야 한다.
- createdAt, id 오름차순과 기존 응답 DTO 및 자산 계산을 유지한다.
- 대결 상태는 목록 필터 조건에 추가하지 않는다.
- 상세 조회, AI 운용 대상 조회, 주문 권한, 계좌 생성·삭제 정책은 변경하지 않는다.
- DB 스키마 변경과 신규 의존성은 필요 없다.

## Review Focus

- 일반 AI와 대결 AI가 모두 제외되는지 확인한다.
- 대결 사용자 계좌는 모든 대결 상태에서 유지한다.
- 타인 계좌 및 비활성 계좌 제외를 유지한다.
- 같은 생성 시각의 ID 정렬을 유지한다.
- 활성 AI 계좌만 있는 회원은 200과 빈 배열을 반환한다.

## Task 1: 목록 필터와 회귀 검증

**Files:** AccountRepository.java, AccountService.java (src/main/java/com/stock_spoon/river_be/account 하위), src/test/java/com/stock_spoon/river_be/account/controller/AccountControllerTests.java, src/test/java/com/stock_spoon/river_be/competition/CompetitionCreationTests.java.

**Interfaces:** AccountService.list(long userId)의 List<AccountListResponse> 계약 유지. Repository 목록 메서드는 findAllByUserIdAndActiveTrueAndAiManagedFalseOrderByCreatedAtAscIdAsc(Long userId)로 변경하고 서비스 및 테스트의 호출부를 함께 갱신한다.

- [x] 기존 accountListIncludesAllOwnedActiveAccountsIncludingDuelAi 테스트를 비AI 정책에 맞게 변경한다. 일반 사용자·대결 사용자 ID 2개만 기대하며 모든 대결 상태, 비활성·타인 제외, 정렬 및 대결 표시를 검증한다.
- [x] 활성 AI 계좌만 있는 회원과 계좌가 없는 회원의 빈 배열 응답을 검증한다.
- [x] CompetitionCreationTests의 목록 기대를 사용자 참가 계좌만 포함하도록 수정한다. AI 운용 대상 기대는 유지한다.
- [x] 같은 메서드를 직접 호출하는 CompetitionUnifiedCreationTests와 CompetitionCancellationServiceTests도 새 이름으로 갱신한다. 전자는 대결 유형별 비AI 계좌 개수로 기대를 수정하고 후자의 취소 후 빈 목록 검증은 유지한다.
- [x] 아래 관련 테스트를 먼저 실행하여 기존 구현에서 새 기대가 실패하는지 확인한다.
- [x] 목록 JPQL에 and a.aiManaged = false를 추가하고 메서드 이름·호출부·한국어 주석을 갱신한다.
- [x] river_be에서 .\gradlew.bat test --tests '*AccountControllerTests' --tests '*CompetitionCreationTests' --tests '*CompetitionUnifiedCreationTests' --tests '*CompetitionCancellationServiceTests' 실행, 실패 0건 확인.

## Task 2: 문서 및 전체 검증

- [x] docs/stockspoon_account_flow.md의 목록 조건·호출 메서드·포함/제외 표를 갱신한다. 과거 완료 계획은 이력으로 유지한다.
- [x] river_be에서 .\gradlew.bat test 및 git diff --check 실행, 실패 및 공백 오류 0건 확인.

## 실행 기록

- 현재 체크아웃된 feat/249-account-list-exclude-ai 브랜치에서 진행한다는 사용자 지시에 따라 별도 브랜치를 만들지 않았다.
- RED: 관련 61개 테스트 중 AI 포함에 따른 기대 불일치 5개를 확인했다.
- GREEN: 목록 쿼리 수정 후 관련 61개 테스트가 모두 통과했다.
- 전체 테스트에서 AccountAssetsTests 3개가 실패했다. 3인자 Account 생성자가 aiManaged=true를 기본값으로 사용하므로, 목록 평가 대상 fixture 5개를 명시적인 비AI 계좌로 변경했다. 생성자 기본 정책과 상세 조회는 유지한다.
- 독립 코드 검토에서 AI 대결 동시 생성 테스트의 생성 검증 약화를 지적했다. 목록 제외 검증과 별개로 대결·계좌·참가자·전략 생성 수를 검증하고, 사용자 대결 동시 생성의 계좌 2개 생성 검증도 유지했다.
- 최종 전체 테스트 성공: 총 698개 중 674개 통과, 24개 건너뜀, 실패·오류 0개. git diff --check 통과. 커밋·push는 하지 않았다.
