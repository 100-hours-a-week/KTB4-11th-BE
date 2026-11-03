# SDD ledger — plan: docs/superpowers/plans/2026-11-03-ai-competition-settings.md

> 2026-11-03 사용자 결정: 별도 설정 API 대신 대결 생성에 nullable agents를 포함한다. ID 전달 방식 질문은 더 이상 필요하지 않다. 이후 작업은 2026-11-03-ai-competition-unified-creation.md를 따른다. 아래 결과는 이전 중간 구현의 이력이다.

- Base: 48489de, feat/217-competition/create-competition/ai-vs-ai. 최신 origin/dev 병합은 사용자 측에서 완료됨을 확인했다.
- Ruling: 네이티브 작업 공간 도구는 중첩 저장소를 거절했다. 사용자가 준비한 217번 브랜치의 깨끗한 작업 트리에서 계속한다. 기존 미추적 계획 문서는 보존한다.
- Pre-flight: 생성과 설정은 대결 ID·AI 수·설정 완료 상태를 공유한다. 대상 대결 ID 전달은 사용자 확인 대기다.
- User decision: AI 2~4명, 설정 완료 전 시작 금지.
- User decision: 이번 범위는 생성·설정까지만. 다수 AI 성적·결과 조회와 운용은 후속이다.
- User decision: risk_level 1=안정형, 2=안정추구형, 3=위험중립형, 4=성장추구형, 5=성장형.
- Ruling: 운용·정산 미구현 상태로 시작하면 기존 1:1 검사에서 실패한다. 이번 범위에서는 설정 완료 후에도 AI 간 대결을 READY로 유지하고 자동 시작 대상에서 제외한다. 실제 운용은 후속 단계에서 연다.
- Ruling: 관심·제외 테마는 빈 배열 허용, 배열 자체와 항목은 null 금지. 테마는 앞뒤 공백 제거, 항목당 255자 이하, 대소문자를 무시한 중복/교집합 거절. 테마 마스터가 없어 임의 목록 검증은 추가하지 않는다.
- Ruling: additional_request는 미입력/null이면 빈 문자열로 저장하고 4000자까지 허용한다. UI 제한 변경이 필요하면 문서와 DTO를 함께 수정해야 한다.
- Baseline: CompetitionCreationTests / CompetitionCancellationServiceTests / CompetitionDailyPerformanceTests PASS.
- Task 2 storage: 새 엔티티 미구현으로 compileTestJava RED 확인 후 CompetitionAgentPersistenceTests 2개 PASS.
- Withdrawal integration: 전략 데이터 존재 시 FK 위반 RED 확인, 자식 테마→전략→참가자 순서 삭제 적용 후 UserWithdrawalTests 및 저장 테스트 PASS.
- Creation test harness fix: 테스트 설정에서 시작 스케줄러 비활성. 기존 테스트 방식처럼 직접 구성하되 TransactionTemplate으로 실제 잠금 트랜잭션을 부여한다.
- Name-availability test fixture fix: 기존 단위 테스트 생성자에 null이던 CompetitionRepository를 mock으로 전달한다. 미설정 대결명도 계좌 생성 전 예약한다.
- Core service verification: CompetitionAiCreationTests / CompetitionAiSettingsTests / CompetitionCreationTests / CompetitionNameAvailability tests PASS. 동시 등록 1건 성공+1건 409, 마지막 참가자 저장 실패 시 전체 롤백 확인.
- Full verification: `./gradlew.bat test spotbugsMain --console=plain` BUILD SUCCESSFUL (2026-11-03). HTTP Controller 추가 전 중간 검증이며 최종 API 완료 검증은 아니다.
- Pending: 사용자는 경로/쿼리 차이를 설명해달라고 요청했고 두 방식의 의미를 설명했다. risk_level 대응은 확정했으나 대결 ID 전달 방식 응답은 아직 대기 중. 해당 HTTP 계약에 의존하는 Controller 및 HTTP 테스트는 미작성이다.
- Remaining: ID 전달 방식 확정 → Controller와 MockMvc 보안/응답 테스트 → 생성·설정 문서 → 최종 검증/리뷰. DB SQL은 작성만 했으며 MySQL에 실행하지 않았다. 커밋·푸시하지 않았다.
