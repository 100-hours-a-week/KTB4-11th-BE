# 대결 상세·결과 응답 통일 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task with tests first.

**Goal:** 사용자 vs AI와 AI 간 대결의 조회 응답을 참가자별 구조로 통일한다.

**Architecture:** 기존 v2 경로에서 중복 최상위 필드를 제거한다. 상세의 참가자별 정보를 유지하고 결과 조회에 참가자 성적·전략·매매 횟수를 조합한다. 정산 데이터와 순위 계산을 재사용하며 DB 스키마와 내부 Winner enum은 변경하지 않는다.

**Tech Stack:** Java, Spring, JPA, Jackson, JUnit, MockMvc, Gradle.

**Spec:** ../specs/2026-11-06-competition-response-unification-design.md

## Global Constraints

- BE만 작업한다. FE·AI·운영 DB를 수정하지 않는다.
- 사용자 진행 승인으로 구현·로컬 커밋까지 수행한다. 푸시·PR은 별도 확인한다.
- user_vs_ai, ai_vs_ai 두 유형 모두 공통 참가자별 구조를 사용한다.
- completed/cancelled의 d_day는 null이다. 결과를 0으로 바꾸지 않는다.
- 단독 winner는 우승 참가자의 display_name 문자열이다. winner_participant_ids는 배열이다.
- 공동 winner는 표시 이름을 ", "로 연결한다. 응답 수익률은 모두 한 자리 HALF_UP이며 저장 정밀도는 유지한다.
- 생성·정산·주문·목록·SQS·리포트·자동 시작은 변경하지 않는다.

## Review Focus

- 삭제 대상 필드가 null 값으로라도 직렬화에 남지 않는지 검사한다.
- 기존 user_vs_ai 참가자 성적을 null로 반환하지 않는지 검사한다.
- AI 생성 순서·참가자 ID·winner 표시 이름이 서로 맞는지 검사한다.
- 현재 시세 없이 결과를 조회할 수 있고 최종 값과 그래프 마지막 점이 일치하는지 검사한다.
- 기존 FE의 최상위 필드 의존이 깨지는 변경임을 전환 문서에 명시한다.

## 실행 전 준비

- [x] 최신 dev와 작업 폴더를 확인하고 이슈에 대응하는 로컬 작업 브랜치를 준비한다. 기존 사용자 변경은 보존한다.
- [x] 공동 우승 시 winner는 "AI 1, AI 2"처럼 연결하도록 사용자가 확정했다.

## Task 1: 상세 응답 통일

**Files:**
- Modify: src/main/java/com/stock_spoon/river_be/competition/dto/CompetitionDetailResponse.java
- Modify: src/main/java/com/stock_spoon/river_be/competition/service/CompetitionDetailService.java
- Test: src/test/java/com/stock_spoon/river_be/competition/CompetitionDetailTests.java
- Test: src/test/java/com/stock_spoon/river_be/execution/service/CompetitionAiOperationTests.java

**Interfaces:** CompetitionDetailService.get(long userId, long competitionId)와 Controller 경로를 유지한다. DTO의 최상위 수익률·그래프만 제거하고 참가자 필드는 유지한다.

- [x] 두 유형에 최상위 cumulative_return_percent/daily_performances 키가 없고 참가자 값은 유지되는 MockMvc·직렬화 테스트를 먼저 작성한다.
- [x] gradlew.bat test --tests '*CompetitionDetailTests' --tests '*CompetitionAiOperationTests'로 새 기대가 실패함을 확인한다.
- [x] DTO 필드와 불필요해진 최상위 사용자 기록 조회·Snapshot 필드를 제거한다. 참가자별 기록·현재 평가 계산은 유지한다.
- [x] 종료 당일 0, 완료·취소 null, 미보유·전략 없음·시세 실패·타인 접근 테스트를 통과시킨다.
- [x] 관련 테스트 통과 후 로컬 커밋한다. 푸시하지 않는다.

## Task 2: 결과 응답 통일과 전략·매매 횟수

**Files:**
- Modify: src/main/java/com/stock_spoon/river_be/competition/dto/CompetitionResultResponse.java
- Modify: src/main/java/com/stock_spoon/river_be/competition/service/CompetitionService.java
- Reuse: src/main/java/com/stock_spoon/river_be/competition/service/CompetitionParticipants.java
- Test: src/test/java/com/stock_spoon/river_be/competition/CompetitionResultTests.java
- Test: src/test/java/com/stock_spoon/river_be/execution/service/CompetitionAiOperationTests.java

**Interfaces:** getCompetitionResult(Long userId, Long competitionId)는 유지한다. CompetitionParticipants.rank로 두 유형 모두 정확한 순위를 계산한다. OrderRepository.countByAccountsAndStatus(accountIds, Order.Status.EXECUTED)와 CompetitionAgentRepository의 대결별 전략 조회를 재사용한다.

- [x] 두 유형의 결과 JSON 키·자료형을 검증하는 실패 테스트를 작성한다. 제거 대상 3개 키 부재, winner 표시 이름, 우승 ID, 참가자별 확정 성적·순위·그래프·전략·횟수, d_day=null을 검사한다.
- [x] gradlew.bat test --tests '*CompetitionResultTests' --tests '*CompetitionAiOperationTests'로 실패를 확인한다.
- [x] 결과 DTO에서 user_final_return_percent/ai_final_return_percent/최상위 daily_performances를 제거한다. 참가자 DTO에 strategy/executed_trade_count를 추가한다.
- [x] 두 유형 모두 참가자별 결과를 조립한다. 사용자와 기존 AI의 strategy는 null, 없는 체결 횟수는 0이다. N명 계좌 횟수는 일괄 조회하고 AI 이름은 기존 생성 순서를 따른다.
- [x] 우승자 ID를 순위로 구하고 해당 참가자 표시 이름으로 winner를 구성한다. 내부 Winner enum과 저장값은 바꾸지 않는다.
- [x] 사용자 우승·AI 우승·공동 우승·2~4명·반올림 동률·누락 기록·미완료 409·타인 404를 검증한다. 결과 조회에 현재 시세 요청이 없는지 검증한다.
- [x] 관련 테스트 통과 후 로컬 커밋한다. 푸시하지 않는다.

## Task 3: 회귀 검증 및 전환 문서

**Files:** docs/competition-ai-operation.md, docs/competition-detail-summary.md 및 위 DTO를 직접 생성하거나 최상위 필드를 검사하는 기존 테스트.

- [x] 생성→시작→기록→정산→상세/결과 HTTP 시나리오의 기대를 새 계약으로 변경하고 값 보존을 확인한다.
- [x] gradlew.bat test spotbugsMain을 실행한다. 실패/오류/건너뜀 수와 이유를 기록하고 git diff --check를 확인한다.
- [x] 두 유형의 실제 직렬화 결과 예시를 문서에 남긴다. 초깃값 300만 원과 수익률 계산, 날짜, 필수/nullable 필드가 일관되는지 확인한다.
- [x] FE 전환 안내에 최상위 필드 삭제, winner 문자열 의미 변경, 공통 participants 사용을 명시한다. FE를 수정하거나 다른 팀에 메시지를 보내지는 않는다.
- [x] 현재/확정 성적 구분, d_day 규칙, 공동 우승 표현과 운영 시작 제한을 문서화한다. 운영 DDL 추가가 없음을 확인한다.
- [x] 독립 리뷰와 필요한 수정·재검증 후 로컬 커밋까지만 완료한다. 푸시와 PR은 사용자 확인 후 진행한다.

## 현재 상태

브랜치 refactor/255-competition/unify-detail-result-response, 시작 HEAD 7bfa17d.
- 최초 계약 테스트 RED: 6개 모두 기존 중복 필드·winner 의미로 실패, 변경 후 GREEN.
- 대결 관련 회귀 실행: 242개 중 1개 실패(기존 draw 문자열 기대), 5개 건너뜀. 이름 연결 계약으로 기대 변경.
- 반올림 경계 RED: 2개 모두 저장된 4자리 재반올림 때문에 5.5 대신 5.6으로 반환됨. 저장 확정 자산에서 한 번 계산하도록 보완.
- Ruling: 두 조회 API에만 반올림 규칙 적용. DB 저장·목록·계좌 API는 유지하므로 다른 화면과 표시 정밀도가 다를 수 있음.
- Ruling: 최종·일별 수익률은 저장된 자산에서 한 자리로 계산. 현재 시세를 쓰지 않고 DB 저장 수익률도 변경하지 않음.
- 실제 HTTP 직렬화 예시는 build/reports/competition-response-{user,ai}-{detail,result}.json에 생성.
- 최종 전체 테스트와 리뷰 결과는 완료 시 아래에 기록한다.

## 리뷰와 보완
- 독립 리뷰 review_response_unification: 알려진 최고 종목 이중 반올림 외 추가 제품 오류 없음. 소유권·현재가 없는 결과 조회·정확한 순위·일별 날짜 순서를 확인했다.
- 최고 종목 반올림 경계 RED: 원래 16.649966...%가 4자리→1자리 이중 반올림으로 16.7%가 됨. 한 번만 반올림해 16.6%를 반환하도록 보완.
- Final: minor (deferred): 결과의 양수 executed_trade_count와 미체결 혼합을 검사하는 전용 신규 케이스는 미추가. 기존 상세 집계 쿼리를 그대로 재사용하고 결과의 0회 계약 및 기존 주문·체결 테스트는 검증함.
- Ruling: 삭제 필드와 winner 의미 변경은 승인된 호환성 변경이므로 FE 담당자와 전환 필요.
- Ruling: AI 전략 누락 거절·완료 상세의 현재 평가·운영 자동 시작 차단은 기존 정책 유지. 비용: 전략 데이터가 훼손되면 결과 조회 복구가 별도 필요하고 실제 AI 운용은 아직 시작되지 않음.

## 최종 검증
- gradlew.bat test spotbugsMain: BUILD SUCCESSFUL. 733개, 실패 0, 오류 0, 건너뜀 24. 신규 계약 테스트 7개 모두 통과.
- 사용자/AI 유형별 JSON 계약, 양수·음수 HALF_UP, 경계 이중 반올림, 완료 d_day=null, 공동 우승 표시 이름, 기존 HTTP 생성→시작→정산→조회 시나리오 검증.
- DB 컬럼·테이블·저장 정밀도·정산 계산 변경 없음. FE·AI·SQS·운영 시작 변경 없음. 운영 DB 접속·DDL 적용 없음.
- API 전환 문서 docs/competition-response-unification.md 작성. 기존 최상위 필드 소비자는 FE 전환 필요.
- git diff --check 통과. 로컬 커밋까지만 수행하고 푸시·PR을 만들지 않는다.
