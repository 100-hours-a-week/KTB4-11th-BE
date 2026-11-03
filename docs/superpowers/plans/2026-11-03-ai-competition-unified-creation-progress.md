# SDD ledger — plan: docs/superpowers/plans/2026-11-03-ai-competition-unified-creation.md

- Base: 48489de, feat/217-competition/create-competition/ai-vs-ai. 기존 분리 방식 미커밋 변경은 이 대화의 작업이므로 통합 방식으로 수정한다.
- Scope: 사용자 요청으로 생성·전략 저장만 구현. AI 간 대결 운용·성적·정산은 후속이며 READY 유지/자동 시작 차단을 보존한다.
- Contract: POST /api/v2/users/me/competitions. user_vs_ai에서 agents 생략/null 허용, ai_vs_ai에서 2~4개 필수 및 인원 일치. 기존 201 생성 응답 유지.
- Pre-flight: Task 1의 생성 DTO/전략 DTO를 Task 2의 참가자 생성 서비스가 사용한다. 설정 완료 상태를 삭제하고 단일 트랜잭션으로 참가자/전략이 모두 존재하게 만든다.
- Ruling: 사용자와 확정한 risk_level 1=안정형~5=성장형을 유지한다. FE dev 30b852ad는 반대로 변환하고 별도 설정 API를 호출하므로 FE 변경 사항을 연동 문서에 명시한다. FE 저장소 변경은 이번 BE 구현에 포함하지 않는다.
- RED: 신규 CompetitionUnifiedCreationTests에서 CompetitionAgentRequest 미구현으로 compileTestJava 실패 확인.
- Task 1~2 in progress: 전략 DTO 추출, nullable agents 중첩 검증, 내부 MANDATORY 트랜잭션 서비스로 저장 로직 이전. 별도 설정 DTO·서비스와 설정 완료 플래그 제거.
- Ruling: 루트 DTO 전체 검증을 서비스 진입에서도 실행해 직접 호출과 HTTP에서 null/범위/중첩 필드 검증을 일치시킨다. 단일 필드 범위 위반은 INVALID_REQUEST, 타입별 유효 범위 위반(예: AI 간 1명)은 INVALID_AI_PARTICIPANT_COUNT로 구분한다.
- HTTP verification: CompetitionUnifiedCreationControllerTests 통과. 실제 보안 필터로 201·nullable agents·중첩 오류·JWT 소유권·CSRF/인증을 확인했다.
- Ruling: 생성된 AI 간 대결을 기존 1:1 상세/취소 경로가 읽으면 참가자 누락 500이 발생한다. 해당 두 미지원 기능은 소유권 확인 후 409 COMPETITION_TYPE_NOT_SUPPORTED로 명시 거절한다. 다수 AI 기능을 구현할 때 이 경계를 교체해야 한다. 새 회귀 테스트에서 기존 500 RED 확인.
- Task 1~2: complete. 통합 생성 2~4명, nullable agents, 마지막 테마/저장 실패 시 대결 포함 전체 롤백, 동시 이름 중복, 기존 생성·계좌명·탈퇴 회귀 검증 PASS.
- Task 3: complete (MySQL 실환경 검증 제외). 실제 HTTP·보안·응답·오류 테스트 PASS, 별도 설정 API/DTO/서비스/플래그 참조 없음. 전체 `./gradlew.bat test spotbugsMain --console=plain` BUILD SUCCESSFUL, 테스트 674개/실패 0/오류 0/건너뜀 18. `git diff --check` PASS.
- Final review: 별도 리뷰 에이전트가 tracked diff와 untracked 구현·테스트·SQL·문서를 검토했으며 actionable P1/P2 없음. 추가 코드 수정 권고 없음.
- Environment limitation: docker.exe는 있으나 Docker Linux engine 파이프가 없어 격리 MySQL을 실행하지 못했다. JPA/H2는 검증했고 실제 DB에는 SQL을 실행하지 않았다.
- Integration: 이 작업의 변경만 217번 브랜치에 커밋하여 보존한다. 원격 push/PR/merge/deploy 및 Google Sheets/FE 수정은 수행하지 않는다. 해당 범위는 별도 연동 작업이다.
