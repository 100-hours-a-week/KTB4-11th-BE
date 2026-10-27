# 본인 활성 계좌 전체 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** GET /api/v1/users/me/accounts에서 타 회원 계좌와 비활성 계좌만 제외하고 본인의 모든 활성 계좌를 반환한다.

**Architecture:** 기존 Controller -> AccountService.list(userId) -> AccountRepository 조회 구조를 유지하고, 목록 쿼리의 대결 전용 AI 계좌 제외 조건만 제거한다.

**Tech Stack:** 기존 Spring MVC, Spring Data JPA, MockMvc/JUnit.

**Spec:** 사용자가 요청한 본인 활성 계좌 전체 반환. 문맥상 '대결 목록'은 계좌 목록 API로 해석하며 /api/v2/users/me/competitions 변경은 포함하지 않는다.

## Global Constraints

- 조회 조건은 JWT 회원 ID 일치와 active=true 두 가지다.
- 생성 시각 오름차순, 같은 시각이면 ID 오름차순 정렬을 유지한다.
- 대결 READY/IN_PROGRESS/COMPLETED/CANCELLED 여부로 목록 계좌를 제외하지 않는다. 계좌 자체가 비활성이면 제외한다.
- 기존 DTO 필드와 계산, AI 운용 대상 조회, 주문 권한 검증은 변경하지 않는다.
- 변경 블록에는 한국어 주석을 추가하고 문서를 실제 구현과 맞춘다.

## Review Focus

- 본인 활성 대결 AI 계좌 누락.
- 타 회원의 대결 AI 계좌 노출.
- 비활성 일반/대결 계좌 노출.
- 목록 노출이 사용자 직접 주문 허용으로 이어진다는 잘못된 가정.
- 기존 대결 생성 테스트의 계좌 개수 및 단일 원소 검증과의 충돌.

## Task 1: 목록 조건 수정과 검증

Files:
- Modify: src/main/java/com/stock_spoon/river_be/account/repository/AccountRepository.java
- Test: src/test/java/com/stock_spoon/river_be/account/controller/AccountControllerTests.java
- Modify tests: src/test/java/com/stock_spoon/river_be/competition/CompetitionCreationTests.java

Interfaces:
- 기존 findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(Long userId)의 시그니처를 유지한다.
- 소비자는 AccountService.list(long userId) 하나이며 응답은 기존 List<AccountListResponse>다.

- [x] 실제 계좌 목록 HTTP 테스트에 본인 일반/일반 AI/대결 사용자/대결 AI 계좌, 타 회원 계좌, 비활성 계좌 fixture를 준비한다.
- [x] 조회 결과 ID가 본인 활성 계좌 전체와 정확히 일치하는지, 타 회원·비활성 계좌는 없는지 검사하고 기존 코드에서 대결 AI 누락으로 실패함을 확인한다.
- [x] Repository의 not exists (대결 참가자이며 aiManaged=true) 조건만 제거한다. 조회 목적을 한국어 주석으로 명시한다.
- [x] 대결 생성 테스트에서 기존 '사용자 참가 계좌 하나만 노출' 기대를 사용자·AI 참가 계좌 모두 노출로 수정한다. AI 운용과 주문 권한 검증은 그대로 둔다.
- [x] 계좌 목록·대결 생성 관련 테스트를 실행해 통과를 확인한다. 대결 양측 계좌 모두 is_duel_account=true이고 기존 자산 계산·정렬이 유지되는지도 검사한다.
- [x] 대결 상태에 따른 추가 제외가 없음을 검증하고, 모든 계좌가 비활성이면 빈 배열인지 확인한다.

## Task 2: 문서와 전체 검증

Files:
- Modify: docs/stockspoon_account_flow.md

- [x] Controller.list(jwt) -> AccountService.list(userId) -> Repository 실제 호출, 전달값, 포함/제외 계좌 표를 작성한다.
- [x] is_duel_account가 실제 참가 관계에서 계산됨을 설명하고, 문서의 현재 목록 DTO와 모순되는 임시값 설명을 해당 범위에서 정정한다.
- [x] 목록 노출과 사용자/AI 주문 권한은 별도임을 명시한다. 현재 DTO는 대결 여부만 표시하고 AI 운용 여부 필드는 없다는 점도 기록한다.
- [x] 전체 Gradle 테스트와 git diff --check를 실행한다.

## 현재 상태

구현·주석·문서 갱신 완료. 전체 Gradle 테스트 성공: 470개 중 452개 통과, 18개 건너뜀, 실패 0개. Controller·Service는 수정하지 않았다. 커밋·push는 수행하지 않았다.
