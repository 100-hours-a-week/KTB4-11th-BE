# AI 참가자별 리포트 저장·조회 구현 계획

**확정 계약:** 기존 GET URI에서 대결 정보와 reports 배열을 반환한다. 각 항목은 participant_id, display_name, report_state, market_analyses, judgment_summaries를 가진다. 사용자 vs AI는 1개, AI 간 대결은 참가자 수만큼이며 미수신 보고서는 pending/빈 배열이다. 정상 수신한 빈 보고서는 completed다. 뉴스 date는 null을 유지한다.

**저장 계약:** 기존 POST /api/v1/competitions/{competition_id}/report에 필수 양수 participant_id를 추가한다. AI 서버와 동시 계약 변경이 필요하다. 인증·대결 소유권 검사는 유지하고 타 대결 참가자·사용자 참가자는 거절한다.

## 단계

1. CompetitionParticipant에 report_ready, 시장 분석·판단 요약 엔티티에 participant FK를 추가한다. 기존 competitions.report_ready는 이관을 위해 남기되 새 저장·조회 판단에서는 사용하지 않는다.
2. CompetitionReportSaveRequest에 participant_id를 추가한다. CompetitionReportService.save는 기존 대결 잠금 안에서 해당 참가자의 내용만 교체하고 해당 참가자의 준비 상태만 완료로 바꾼다.
3. CompetitionReportResponse에 ReportItem 목록을 추가한다. get은 참가자 목록 기준으로 내용을 묶어 반환한다. AI 간 대결은 기존 에이전트 position 순서와 AI 1/AI 2 이름을 사용한다. 일관된 조회를 위해 REPEATABLE_READ 읽기 트랜잭션을 사용한다.
4. 기존 보고서·빈 보고서 완료 상태의 단일 AI 이관 SQL을 작성한다. 모호한 다중 AI 과거 데이터는 중단·수동 확인한다. FK에 맞게 회원 탈퇴 삭제 순서를 바꾼다.
5. 실제 HTTP 저장/조회·인증·검증, 다중 AI 분리·재전송·동시 저장·실패 롤백·미수신 상태·기존 회원 탈퇴를 검증하고 호출 흐름과 배포 안내를 갱신한다.

**제외:** 운영 SQL 실행, AI 서버 수정, FE 코드 변경, AI 자동 시작 정책 변경. 검증 후 사용자 요청에 따라 현재 브랜치에 커밋·푸시하고 dev 대상 PR을 생성한다.

## 구현 결과

- 1~5단계를 구현했다. 보고서 본문은 참가자에 연결하고 기존 대결 컬럼도 유지한다. 구버전 competitions.report_ready는 새 상태 판단에 사용하지 않는다.
- 새 reports 응답 테스트가 기존 코드에서 PathNotFoundException으로 실패하는 것을 확인한 뒤 구현했다.
- 다중 AI의 표시 순서·일부 pending·개별 교체·빈 보고서 완료·다른 AI 보존·같은/다른 AI 동시 저장·실패 롤백을 검증했다. 실제 HTTP로 필수 ID 검증과 잘못된 참가자 거절도 확인했다.
- `gradlew.bat test --offline --tests 'com.stock_spoon.river_be.competition.*' --tests '*UserWithdrawalTests' --tests '*AccountDeletionTests'`: 253개 중 248개 통과, 5개 건너뜀, 실패·오류 0건.
- `git diff --check` 통과. 운영 MySQL 이관·실제 AI 콜백·AWS 연동은 미검증이다.
- 현재 계약·호출 흐름은 docs/competition-participant-reports.md, DB 적용은 docs/competition-report-deployment.md와 db/competition-reports-participant.sql에 정리했다.

## PR #263 CI 후속 수정

- 전체 CI에서 SecurityJwtTests의 저장 요청 2건과 execution.service.CompetitionAiOperationTests의 종료 기대값 1건이 이전 계약을 사용해 실패했다. 앞선 대결 패키지 중심 검사에는 이 클래스들이 포함되지 않았다.
- 동일한 3개 실패를 로컬에서 재현했다. 보안 테스트에는 필수 participant_id와 실제 AI 참가자를 준비하고, 잘못된 제목 검증과 저장 대상 참가자 확인을 유지했다.
- AI 간 대결 종료 테스트는 기존의 Outbox 없음 대신 참가자별 PENDING 요청 1건, 고유 requestId, 반복 완료 시 중복 없음까지 검증한다.
- 서비스·CI 설정·DB SQL은 변경하지 않았다. 로컬 `gradlew.bat test --offline` 전체 실행: 748개 중 724개 통과, 24개 건너뜀, 실패·오류 0건. `git diff --check` 통과.
- 수정 커밋 e0c4e80을 동일 브랜치에 푸시했고 [Backend Dev CI](https://github.com/100-hours-a-week/KTB4-11th-BE/actions/runs/42485462204) 통과를 확인했다.
- 사용자 확인: 2026-11-09 두 이관 SQL을 인프라 담당자에게 전달 완료했다. 운영 DB 적용 완료는 아직 확인되지 않았다. 배포 후 DB 오류 조사 시 docs/competition-report-deployment.md의 상태와 실제 스키마를 확인한다.
