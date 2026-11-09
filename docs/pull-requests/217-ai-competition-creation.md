# PR 제목

feat: AI 간 대결과 에이전트 전략 통합 생성

# PR 본문

기존 대결 생성 API가 user_vs_ai만 처리하던 제한을 확장해, ai_vs_ai 생성 요청에서 AI 2~4명의 전략을 함께 저장한다. FE는 각 화면의 입력을 모아 `POST /api/v2/users/me/competitions`를 한 번 호출하면 된다.

- user_vs_ai는 agents 생략/null을 허용하고 기존 201 응답을 유지한다.
- ai_vs_ai는 agents 필수·인원 일치·위험도/테마/추가 요청을 검증한다. 대결·계좌·참가자·전략을 하나의 트랜잭션으로 저장한다.
- 동일 사용자 잠금으로 중복 이름 경쟁을 막고, 실패 시 전체 롤백한다. 회원 탈퇴 시 전략 자식 테이블도 정리한다.
- 실제 다수 AI 운용·정산은 후속 범위다. AI 간 대결은 READY 유지/자동 시작 제외, 1:1 전용 상세·취소는 409로 명시 거절한다.

## 검증

- 최신 전체 테스트 679개, 실패/오류 0, 건너뜀 23 및 SpotBugs 통과. 건너뜀에는 아래 별도 실행한 MySQL opt-in 테스트 5개가 포함된다.
- 실제 MySQL 8.4.11에서 마이그레이션·기존 데이터 보존·JPA 저장·CHECK/UNIQUE·전체 롤백·탈퇴 정리 5개 테스트 통과.
- 실제 운영 DB에는 DDL을 실행하지 않았다.

## 배포 시 필요한 DB 작업

`db/competition-ai-settings.sql`을 BE 배포 전에 적용한다. competitions.competition_type ENUM에 AI_VS_AI를 추가하고, competition_agents 및 관심/제외 테마 테이블 총 3개를 생성한다. ai_settings_complete 컬럼은 필요 없다.

회원 탈퇴도 신규 테이블을 참조하므로 DDL 선행이 필수다. 운영 스키마 preflight, 잠금·부분 실패·롤백 주의사항은 `docs/competition-ai-production-schema.md`에 정리했다.

## FE 계약 전달 사항

이번 PR은 BE만 변경한다. FE는 참고용이며 FE 변경은 없다. 위험도는 1=안정형, 5=성장형이다. 현재 FE의 반대 매핑과 대결 생성 후 별도 설정 API 호출은 통합 계약에 맞춰 조정해야 한다. 통합 요청/오류/제한은 `docs/competition-ai-creation.md`를 참조한다.
