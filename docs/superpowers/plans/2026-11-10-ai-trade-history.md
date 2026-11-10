# AI 간 대결 매매 내역·매매 근거 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 간 대결 소유자가 진행 중 각 AI의 매매 내역, 주문 내역, 해당 주문의 매매 근거 상세를 조회한다.

**Architecture:** 기존 check()의 공개 정책은 보존한다. 세 조회 경로에는 checkTradeHistory()를 적용하고 참가 계좌·소유권 검사는 내부 공통 로직을 재사용한다. 현재 매매·주문 내역은 연결되어 있으므로 AiReportService의 호출을 변경하는 것이 남은 구현이다.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Gradle, 로컬 H2 테스트.

**Spec:** 이 문서의 확정 요구사항 및 사용자와 합의한 조회 목적별 검사 방식.

## 확정 요구사항

| 대상 | READY | IN_PROGRESS | COMPLETED | CANCELLED |
|---|---|---|---|---|
| AI vs AI의 세 조회 경로 | 차단 | 허용 | 허용 | 차단 |
| User vs AI의 AI 계좌 | 차단 | 차단 | 허용 | 차단 |

User vs AI의 사용자 계좌, 일반 계좌의 기존 접근 정책은 유지한다. 허용은 기존 소유권·주문 소속·AI 보고서 존재 여부 등 검증을 통과한 경우를 뜻한다.

## Global Constraints

- 기존 public check(long userId, long accountId, boolean requireCompetition)의 동작을 변경하지 않는다.
- checkTradeHistory(long userId, long accountId, boolean requireCompetition)를 세 조회 경로에서 사용한다.
- 타인 계좌 및 다른 계좌의 주문을 조회할 수 없어야 한다.
- API URI·응답 형식·페이지네이션·주문 및 보고서 데이터 검증을 유지한다.
- DB 스키마·AI 서버 계약·대결 시작 기능 변경과 데이터 이관은 없다.

## 선택한 방식

공통 check()의 조건을 변경하면 그 메서드를 사용하는 모든 기능의 공개 정책이 바뀐다. 별도 checkTradeHistory()를 세 경로에 적용하면 기존 check()의 계약을 보존하면서 요청한 범위만 공개할 수 있다. 검사 전체를 복제하지 않는다.

## Review Focus

- 진행 중 공개가 User vs AI까지 확대되지 않는지 확인한다.
- READY·CANCELLED에서 매매 근거 상세까지 차단되는지 확인한다.
- 매매 근거 요청의 orderId가 다른 참가자의 주문이면 기존대로 거절되는지 확인한다.
- report가 없거나 AI 주문·체결 주문이 아니면 기존 검증을 유지하는지 확인한다.
- 호출부가 없어져도 기존 check() 자체의 완료 전 차단 계약이 유지되는지 직접 테스트한다.

## Task 1: 매매 근거 상세에 기존 별도 검사 연결

**Files:**
- Modify: src/main/java/com/stock_spoon/river_be/report/service/AiReportService.java — 접근 검사 호출 한 곳 변경.
- Modify: src/test/java/com/stock_spoon/river_be/competition/CompetitionTradeHistoryTests.java — 진행 중 근거 공개와 상태·소유권 검증.
- Create: src/test/java/com/stock_spoon/river_be/competition/CompetitionHistoryAccessTests.java — 두 public 검사 진입점의 계약 검증.
- Reference: CompetitionHistoryAccess, CompetitionTradeHistoryService, OrderHistoryService — 이미 구현된 정책·연결을 확인한다.

**Interfaces:**
- Consumes: CompetitionHistoryAccess.checkTradeHistory(long userId, long accountId, boolean requireCompetition).
- Produces: 기존 AiReportService.get(long userId, long accountId, long orderId)의 응답 계약을 유지하면서 진행 중 AI vs AI 접근을 허용한다.

- [ ] **Step 1: 실패 테스트 수정·추가**
  - 기존 exposesEachAiTradeHistoryWhileRunningButKeepsReportsPrivate를 exposesEachAiTradeHistoryAndReportWhileRunning으로 변경한다.
  - AI 2·3·4명 각각의 진행 중 근거 상세 요청을 200으로 기대하고 reason이 해당 주문의 저장된 근거와 같은지 확인한다. 참가자별 서로 다른 근거를 저장해 구분한다.
  - READY·CANCELLED의 근거 상세는 403, COMPLETED는 200으로 확인한다.
  - 타인 근거 상세는 기존 403, 본인 다른 참가자의 orderId를 잘못된 accountId와 조합한 요청은 404로 확인한다.
  - 기존 User vs AI·일반 계좌 테스트와 AiReportService의 보고서 적격성 테스트를 유지한다.
  - CompetitionHistoryAccessTests에서 동일한 진행 중 AI vs AI 참가자에 check()는 COMPETITION_AI_HISTORY_HIDDEN 예외, checkTradeHistory()는 예외 없음으로 직접 검증한다. 저장소만 모의 처리하고 실제 검사 로직을 호출한다.

- [ ] **Step 2: 실패 확인**
  - 실행: `.\gradlew.bat test --tests '*CompetitionTradeHistoryTests' --tests '*CompetitionHistoryAccessTests' --console=plain`
  - 기대: 새 근거 상세 200 기대값이 현재 403으로 실패한다. 직접 검사 계약 테스트는 기존 구현에서 통과한다.

- [ ] **Step 3: 최소 구현**
  - AiReportService.get()의 `competitionAccess.check(userId, accountId, false)`를 `competitionAccess.checkTradeHistory(userId, accountId, false)`로 교체한다.
  - check()와 나머지 검증 순서·예외 처리는 유지한다.

- [ ] **Step 4: 검증 및 리뷰**
  - Step 2의 테스트 명령을 재실행해 모두 통과하는지 확인한다.
  - `.\gradlew.bat test --console=plain`으로 전체 회귀를 확인한다.
  - `git diff --check` 및 세 호출부와 기존 check() 보존 여부를 리뷰한다.
  - 결과를 기록한다. commit·push·배포는 별도 요청에 따른다.

## 현재 상태

매매 근거 상세도 checkTradeHistory로 연결했다. 진행 중 근거 조회 테스트 3건이 변경 전 403으로 실패함을 확인하고, 변경 후 전체 테스트를 실행했다. 792건 중 768건 통과, 24건 건너뜀이며 실패는 없다. 별도 읽기 전용 리뷰와 git diff --check를 통과했다. 현재 HTTP 테스트에서 진행 중 reason 본문과 대기·취소의 근거 접근 차단을 검증한다. 계획의 별도 단위 테스트 파일 및 참가자별 서로 다른 근거 데이터 확장은 추가하지 않았다. commit·push·배포는 하지 않았다.
