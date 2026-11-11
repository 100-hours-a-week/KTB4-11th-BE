# AI 간 대결·전략 통합 생성

2026-11-03 확정한 통합 계약이다. 별도 `POST /api/v3/users/me/competitions/ai-settings`는 구현하지 않는다.

## 요청과 응답

`POST /api/v2/users/me/competitions`에 사용자 access_token 쿠키와 CSRF 쿠키·`X-XSRF-TOKEN` 헤더를 전달한다. 사용자 ID는 JWT subject에서 읽는다.

```json
{
  "competition_name": "전략 비교",
  "competition_type": "ai_vs_ai",
  "start_date": "2026-11-03",
  "end_date": "2026-12-03",
  "initial_capital": 3000000,
  "ai_participant_count": 2,
  "agents": [
    {
      "risk_level": 1,
      "preferred_themes": ["금융"],
      "excluded_themes": [],
      "additional_request": ""
    },
    {
      "risk_level": 5,
      "preferred_themes": ["반도체", "2차전지"],
      "excluded_themes": ["게임"],
      "additional_request": "장기 성장 가능성이 높은 기업을 중심으로 투자해주세요."
    }
  ]
}
```

날짜 예시는 요청 당일로 변경해야 한다. 성공하면 기존 응답 형식을 유지해 **201 Created**를 반환한다.

```json
{"message":"success","competition_id":1,"state":"ready"}
```

위 ready는 대기 경로 예시다. 장중 즉시 처리 성공 시 같은 응답의 state는 in_progress다. `agent_id`, `participant_id`는 내부에서 발급해 연결하며 생성 응답에 추가하지 않는다. 이전 분리 API 설계의 설정 전용 응답은 제거했다.

| 조건 | user_vs_ai | ai_vs_ai |
|---|---|---|
| AI 참가자 수 | 1, 생략 시 기본값 1 | 2~4, 실제 인원을 명시 |
| agents | 생략 또는 null | 필수, 항목 수와 AI 수 일치 |
| 계좌 구성 | 사용자 1개 + AI 1개 | 전부 AI 계좌 N개 |
| 설정 저장 | 별도 전략 없음 | 참가자마다 전략 1개 |
| 생성 상태 | 기존 장 시간 정책으로 ready/in_progress | 장중 즉시 전송·시작 성공 시 in_progress, 장외·실패 시 ready |

user_vs_ai에 null이 아닌 agents를 전달하면 400으로 거절한다. 입력한 전략을 조용히 버리지 않는다.

## 입력 규칙

- 기존 온보딩·이름·기간·초기금 규칙을 유지한다. 이름은 20자 이하이며 공백만 입력할 수 없다. 초기금은 100만~1억 원, 시작일은 한국 시간 오늘, 종료일과의 날짜 차이는 최소 3일이다.
- 초기금은 각 계좌에 동일하게 지급한다. 예를 들어 300만 원/AI 3명은 각 계좌 300만 원이다.
- agents는 2~4개이고 null 항목을 허용하지 않는다. 배열 순서를 0부터 agent_position으로 저장한다.
- risk_level은 필수 정수 1~5다.
- preferred_themes와 excluded_themes는 필수 배열이며 빈 배열을 허용한다. 테마 항목은 null/공백 금지, 255자 이하다. 앞뒤 공백을 제거하고 대소문자를 무시하여 배열 내부 중복과 관심·제외 교집합을 거절한다.
- 테마 마스터 검증은 아직 없다. 업종 sector와 필드명을 혼용하지 않는다.
- additional_request는 생략/null/빈 문자열을 허용한다. null은 빈 문자열, 앞뒤 공백은 제거하며 최대 4000자다.

| risk_level | 투자 성향 |
|---|---|
| 1 | 안정형 |
| 2 | 안정추구형 |
| 3 | 위험중립형 |
| 4 | 성장추구형 |
| 5 | 성장형 |

## 실제 호출과 저장 흐름

```text
FE: 대결 조건 입력 → N개 전략 화면 입력값 보관 → 마지막 화면에서 POST 1회
  → competition.controller.CompetitionController.createCompetition(jwt, request)
  → competition.service.CompetitionService.createCompetition(userId, request) [단일 트랜잭션]
      → 루트/중첩 DTO 검증
      → UserRepository.findLockedById(userId): 사용자 잠금·온보딩 확인
      → 유형·인원·agents·시작일·기간·이름 검사
      → AccountRepository / CompetitionRepository: 이름 중복 검사
      → CompetitionRepository.save(): AI_VS_AI 대결 저장
      → CompetitionAgentCreationService.createParticipants(competition, agents)
          → 각 전략의 테마 정규화·중복/교집합 검증
          → AccountRepository.save(): 해당 사용자 소유 AI 계좌·초기금 지급
          → CompetitionParticipantRepository.save(): 대결 ↔ 계좌 연결
          → CompetitionAgentRepository.save(): 참가자 ↔ 전략·순서 연결
  → 전체 커밋 후 CompetitionCreateResponse → 201
```

현재 HTTP 생성은 CompetitionCreationCoordinator를 거쳐 생성 커밋 후 장중 전송·시작을 시도한다. 아래 생성 서비스 설명은 저장 단계이며 최종 응답은 실제 상태로 재구성된다. [즉시 처리 해설](competition-immediate-start.md)을 참고한다.

Controller는 JWT subject를 Long 사용자 ID로 바꾸고 JSON을 변환한 CompetitionCreateRequest와 함께 Service에 전달한다. Service는 HTTP 외부에서 직접 호출해도 DTO를 검증하며, 사용자 잠금을 잡아 동일 소유자의 계좌·대결 이름 경쟁을 직렬화한다. 유형이 user_vs_ai면 기존 사용자/AI 두 계좌 생성 경로를 사용한다.

AI 간 대결이면 Service가 저장한 Competition과 요청의 agents를 내부 생성 서비스에 넘긴다. 내부 메서드는 `Propagation.MANDATORY`이므로 부모 트랜잭션 없이 독립 등록할 수 없다. N개 계좌·참가자·전략을 모두 저장한 뒤 부모가 기존 생성 응답을 구성한다. 테마 검증이나 마지막 저장·커밋 중 실패하면 대결까지 모두 롤백한다. 빈 대결이나 부분 설정을 성공 결과로 남기지 않는다.

별도 설정 완료 컬럼·미완성 대결 연결 ID·설정 등록 API는 없다. 동일 이름 재요청은 기존 중복 이름 정책으로 409이며 성공 응답을 재전송하는 멱등 API는 아니다. 응답 유실 시 FE는 대결 목록을 확인한 후 재시도 여부를 판단한다.

기존 `@DbWriteReplayBusinessCommand(COMPETITION_CREATE)`가 통합 DTO를 직렬화하므로 agents도 동일 명령 payload에 포함된다. 새로운 명령 유형이나 독립 설정 명령은 추가하지 않는다. replay/audit 설정이 비활성인 환경에서는 해당 기능은 실행되지 않는다.

## 오류와 현재 지원 범위

| HTTP | code | 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON·필수값·단일 필드 범위·중첩 전략 오류, AI 간 agents 누락, user_vs_ai에 agents 제공 |
| 400 | INVALID_COMPETITION_TYPE | 지원하지 않는 유형 |
| 400 | INVALID_AI_PARTICIPANT_COUNT | 유형별 인원 위반(예: ai_vs_ai 1명) |
| 400 | AGENT_COUNT_MISMATCH | 유효한 크기의 전략 배열과 AI 수가 불일치 |
| 400 | INVALID_AGENT_THEMES | 정규화 후 중복 테마·관심/제외 충돌 |
| 400 | INVALID_START_DATE / INVALID_COMPETITION_PERIOD | 기존 시작일/기간 정책 위반 |
| 409 | ONBOARDING_REQUIRED / DUPLICATE_ACCOUNT_NAME | 온보딩 미완료 / 기존 본인 계좌·대결명 중복 |
| 401/403 | 기존 보안 오류 | 미인증 / CSRF 실패 |

AI 간 대결은 생성 커밋 후 장중이면 즉시 전송·시작을 시도한다. 성공하면 in_progress, 장외·전송 실패 등에는 ready를 반환하며 스케줄러가 재시도한다. 시작 조건과 배포 순서는 [AI 대결 시작 계약](competition-ai-start.md)을 따른다.

상세의 참가자별 자산·수익률·그래프, 다수 AI 일별 성과·정산·결과·취소는 [운용 확장 계약](competition-ai-operation.md)을 참고한다. 생성 요청과 성공 응답은 그대로다. 결과는 완료 전 기존 409 `COMPETITION_NOT_COMPLETED`를 반환한다. 다수 AI 리포트 계약은 후속 범위다.

## FE 연동 변경

확인 기준: FE dev `30b852add179355153fc050cbc733db9c67079e6`.

- `src/screens/duel/DuelAiStrategySettingContainer.tsx`: 현재 대결 생성 성공 후 ai-settings를 호출한다. 마지막 화면에서 생성 요청에 agents를 넣어 한 번 호출하도록 변경해야 한다.
- `src/features/ai/types/duel.ts`: CreateCompetitionRequest에 `agents?: AiAgentSettingRequest[] | null`을 추가하고 별도 설정 mutation·응답 의존을 정리해야 한다.
- `src/features/ai/types/aiStrategy.ts`: 현재 toRiskLevel은 화면 선택지의 index+1이어서 성장형=1, 안정형=5다. 위 표의 명시적인 매핑으로 수정해야 한다. UI의 표시 순서를 바꾸는 것과 API 숫자 의미를 섞지 않는다.
- 현재 FE는 관심·제외 테마를 모두 하나 이상 선택해야 다음으로 넘어간다. BE는 빈 배열도 허용한다. FE에서 선택 입력으로 바꿀지는 UI 정책에 따라 결정한다.

이 변경은 BE 저장소의 구현과 문서다. FE 코드는 수정하지 않았다.

## DB 적용과 검증

`db/competition-ai-settings.sql`은 기존 competitions/competition_participants 이후 앱 배포 전에 한 번 적용한다. 새 전략 테이블과 테마 자식 테이블이 회원 탈퇴 SQL에서도 사용된다. 실제 컬럼 타입·기존 유형을 확인하고 적용한다. ai_settings_complete 컬럼은 추가하지 않는다.

MySQL DDL은 JPA/H2 테스트만으로 운영 적용이 검증되는 것은 아니다. 운영 DB에 이 파일을 자동 실행하지 않는다. 새 AI_VS_AI 데이터가 생긴 뒤 구버전 코드로 돌아가면 구버전 enum이 이를 읽지 못하므로 롤백 전에 데이터 호환을 확인해야 한다.

2026-11-03 후속 검증에서 격리 MySQL 8.4.11의 마이그레이션·JPA 저장·제약·롤백·탈퇴 정리 5개 테스트가 통과했다. 운영 스키마 변경 목록과 적용 순서는 [운영 DB 변경 안내](competition-ai-production-schema.md)를 따른다. 실제 운영 DB에는 적용하지 않았다.

테스트는 전략 저장/순서/유일성, 2~4명 통합 생성, nullable agents, 마지막 저장 오류 전체 롤백, 동시 이름 중복, 실제 HTTP/JWT/CSRF, 회원 탈퇴의 전략 정리를 포함한다.
