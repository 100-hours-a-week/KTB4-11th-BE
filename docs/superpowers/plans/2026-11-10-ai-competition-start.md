# AI competition start Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this session.

**Goal:** 모든 AI의 준비 완료 통지를 받은 대결을 장중에 자동 시작한다.

**Architecture:** 승인된 채팅 설명의 콜백 방식을 구현한다. 대결에 준비 완료 시각을 저장하고 기존 시작 스케줄러와 잠금을 재사용한다. SQS 발송 완료와 AI 준비 완료를 구분한다.

**Tech Stack:** Java 21, Spring MVC/Security/JPA, H2 tests, MySQL migration SQL.

**Spec:** 이 채팅에서 승인한 파일별 구현 해설 및 GitHub #274.

## Global Constraints

- POST /api/v1/competitions/{competitionId}/ai-setup/complete, body event_id, response 204.
- 기존 리포트와 같은 사용자 ID subject의 AI access-token cookie 인증 및 소유권 확인.
- AI 서버는 모든 참가자의 전략을 영구 저장한 뒤 통지한다. AI 소비자 수정은 외부 연동 과제로 명시한다.
- 중복 성공은 최초 시각을 보존한다. 취소·최초 통지 시 실제 종료 시각 경과는 409.
- 종료일을 연장하지 않는다. 만료한 READY는 자동 시작에서 제외하고 취소 가능하게 둔다.
- 준비 지연 중 READY 유지. 발송 실패는 기존 Outbox 재시도, 완료 HTTP 실패는 AI가 같은 event_id로 재시도한다.
- 기존 이슈 전용 브랜치에서 직접 구현하며 push/배포/원격 SQL은 수행하지 않는다.

## Review Focus

- SQS 발송 성공 후 DB 커밋 실패: 유효한 완료 통지는 PENDING도 인정하고 SENT로 복구.
- 중복·취소·시작 동시성: 대결 다음 Outbox 순서의 잠금, 최초 완료 시각 보존.
- 주말 종료일: 달력 종료일 대신 tradingEndsAt으로 실제 만료 판정.
- 타 사용자·타 이벤트·잘못된 토큰: 소유권과 event_id 및 AI actor 검사.
- 준비 전 계좌 비공개, 준비 후 시작 이후에만 기존 AI snapshot에 계좌 노출.

## Task 1: 준비 완료와 자동 시작

- [x] 테스트: 실제 생성 → 미준비 대기 → 콜백 → 장중 시작 → AI 계좌 노출. 장외·취소·만료·잘못된 이벤트·중복도 검사.
- [x] RED 확인: 최초 실행의 401은 미커밋 회원/인증 별도 트랜잭션 fixture 문제였다. 보정 후 기존 AI 시작 차단 조건을 복원한 회귀 검증에서 AI 2/3/4명 모두 IllegalStateException으로 실패함을 확인했다.
- [x] Competition에 aiSetupCompletedAt, markAiSetupCompleted(Instant) 추가.
- [x] 완료 DTO/controller/service와 Outbox 이벤트 조회, 인증 규칙 추가.
- [x] Repository에 준비된 AI 포함, scheduler에 실제 종료 시각 검사 추가.
- [x] GREEN 확인 및 HTTP 인증 테스트.

## Task 2: 문서·회귀 검증

- [x] MySQL nullable timestamp 컬럼 추가 SQL 및 API/실패/배포 순서 문서 작성.
- [x] 전체 test와 spotbugsMain 및 git diff --check 실행.
- [x] 자체 검토와 별도 코드 리뷰 후 필요한 오류 수정.

## Progress / decisions

- 기존 브랜치 feat/274-ai-competition-start-scheduler, 시작 시 작업 트리 clean.
- 기본 Gradle 캐시가 sandbox 밖이므로 승인된 Gradle 실행으로 검증.
- Ruling: 설계에 남아 있던 기간 정책은 기존 종료일 유지로 확정. 자동 취소와 새 실패 상태는 추가하지 않음. 만료 대결은 수동 취소 가능.
- 1차 전체 검증: test 802개, 실패/오류 0, skipped 24; spotbugsMain PASS.
- 별도 리뷰: 잠금 대기 뒤 장 마감/날짜 경과 문제를 재현한 테스트 2개 RED 확인 → 잠금 이후 시간 재검사 → GREEN. 신규 계좌 노출 테스트의 실시간 날짜 의존을 제거하고 현재 문서 4곳의 이전 시작 제한 설명도 수정.
- Task 1: complete. 신규 준비/시작 테스트 및 기존 생성·인증 회귀 검증. 최종 전체 검증 결과는 아래 기록.
- Task 2: complete. 최종 `./gradlew.bat test spotbugsMain` BUILD SUCCESSFUL, 804 tests / 0 failures / 0 errors / 24 skipped. `git diff --check` PASS.
- 별도 리뷰의 3개 지적(잠금 후 시각, 테스트 실제 날짜 의존, 문서 불일치)을 반영했다. 미해결 리뷰 지적 없음.
- 작업 결과는 현재 이슈 브랜치의 미커밋 변경으로 유지한다. 원격 push/PR/DB 적용/배포 및 AI 서버 실연동은 수행하지 않았다.
