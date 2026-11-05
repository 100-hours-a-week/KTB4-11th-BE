# AI 간 대결 운용 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans; implement each task with failing tests first.

**Goal:** 계좌별 기존 주문을 재사용하면서 N명 성과 기록·정산·조회·취소를 지원한다.
**Architecture:** 참가자 구성 검사와 순위 비교를 공유하고 기존 v2 응답에 필드를 추가한다. 운영 시작 제한은 유지한다.
**Tech Stack:** Java, Spring, JPA, JUnit, Gradle.
**Spec:** ../specs/2026-11-06-ai-competition-operation-design.md (사용자 go 승인).

## Global Constraints
- BE only. SQS 전략 전달 제외. 푸시·PR·배포·운영 DB 작업 금지.
- 생성 계약 유지. 자동 시작 차단 유지. 테스트만 진행 상태 fixture 사용.
- 기존 user_vs_ai 계약 보존. 순위는 반올림 전 비율, 1,1,3.

## Review Focus
- 동일 종목을 여러 AI가 매매해도 계좌 자산이 섞이지 않음.
- 잘못된 참가자 구성은 정산·취소 전에 차단.
- 마지막 시세 실패 시 부분 기록·정산 커밋 금지.
- 공동 우승과 반올림 결과가 같은 미세 차이를 구분.
- AI 대결에서 기존 단일 AI 리포트 Outbox를 발행하지 않음.

## Task 1: 계좌 격리와 N명 일별 기록
- [x] CompetitionAiOperationTests: READY 체결 차단, 테스트 진행 fixture에서 2~4명 계좌 격리, N명 일별 성과·재실행·롤백 테스트 추가.
- [x] 실패 확인: gradlew test --tests '*CompetitionAiOperationTests'.
- [x] CompetitionParticipants 유효 구성 검사 작성, CompetitionDailyPerformanceService의 1:1 검사 확장.
- [x] 관련 테스트 통과 후 로컬 커밋.

## Task 2: N명 정산과 취소
- [x] 동일 fixture로 N명 종료·공동 1위·시세 실패 롤백·리포트 미발행·N명 취소 테스트 추가, 실패 확인.
- [x] CompetitionCompletionService, CompetitionCancellationService 확장. CompetitionStandings 비교 함수 재사용.
- [x] 관련 테스트 통과 후 로컬 커밋.

## Task 3: 참가자별 상세·결과
- [x] 상세 자산·수익률·날짜별 기록, 결과 최종 자산·순위·공동 우승 ID HTTP/서비스 테스트 추가, 실패 확인.
- [x] CompetitionDetailResponse/Service, CompetitionResultResponse, CompetitionService 확장.
- [x] 전체 test spotbugsMain 및 기존 HTTP 보안·1:1 회귀 검증.
- [x] API 예시·운영 제한 문서 작성, 독립 코드 리뷰 후 필요한 수정, 로컬 커밋.

## Progress
- 코드 확인 및 기준 테스트 33개 통과 (전 턴).
- 시작 HEAD: 28bec4e. 브랜치: codex/ai-competition-operation.
- 계좌 처리에는 이미 account_id 소유권·출처·상태 검사 존재. 메시지 계약 변경 없이 통합 검증한다.

## 실행 및 리뷰 기록
- 신규 통합 테스트 최초 RED: 9개 중 7개 실패. 기존 1:1 성과·정산·취소 제한 확인. READY 테스트는 잠금 쿼리에 트랜잭션이 없던 fixture 오류를 수정.
- 관련 테스트 GREEN: 49개 통과. 기존 응답 필드 부재 기대는 승인된 필드 추가 계약에 맞게 변경.
- 전체 첫 실행: 715개 중 취소 미지원 기대 테스트 1개 실패. 신규 취소 지원 계약에 맞게 생성 후 취소 성공으로 수정.
- 독립 리뷰: review_ai_operation 읽기 전용. P2 2건(완료 상세의 현재 평가액/확정액 혼동, 중복 전략 위치) 및 누락 전략 정산 검증을 모두 실패 테스트로 재현 후 수정.
- Ruling: 취소는 전략이 훼손되어도 유효 참가자 계좌를 안전하게 닫을 수 있도록 전략 존재를 요구하지 않음. 정산·일별 기록·결과는 정확한 전략 연결을 요구함. 비용: 훼손 전략 데이터의 복구는 별도 작업.
- Ruling: 기존 1인용 리포트는 user_vs_ai에서만 생성. 비용: AI 간 리포트는 후속 연동 전 제공되지 않음.
- Ruling: 운영 시작 차단 유지, 주문 메시지 변경 없음, 기존 상세 시세 조회 실패 정책 유지. 비용: 실제 운용·외부 연동 성공은 이번 테스트만으로 보장하지 않음.
- 전체 테스트·SpotBugs 최종 결과는 최종 커밋 전 아래에 기록한다.
- 단계별 코드가 공통 통합 테스트와 연결되어 하나의 로컬 기능 커밋으로 보존한다. 푸시하지 않는다.

- 최종 검증: gradlew test spotbugsMain BUILD SUCCESSFUL. 총 722개, 실패 0, 오류 0, 건너뜀 24. 신규 통합 테스트 16개 통과. git diff --check 통과. 격리 MySQL opt-in은 이번 변경에서 실행하지 않음.
