# AI 간 대결 생성: 운영 DB 스키마 변경 안내

## 적용할 변경

이번 BE 기능 배포에는 DB 변경이 필요하다. 아래는 저장소의 기존 DDL과 구현을 기준으로 한 요구사항이며 **운영 DB에 직접 접속해 확인한 결과가 아니다**. 운영 SQL은 실행하지 않았다.

| 대상 | 변경 | 기존 데이터 영향 |
|---|---|---|
| competitions.competition_type | ENUM에 AI_VS_AI 추가 | 기존 USER_VS_AI 유지. 실제 운영 컬럼 타입과 저장값 확인 필요 |
| competition_agents (신규) | agent_id PK, participant_id UNIQUE/FK, agent_position, risk_level, additional_request | 기존 참가자에 설정을 소급 생성하지 않음 |
| competition_agent_preferred_themes (신규) | agent_id FK, theme_position, theme | 관심 테마 순서 저장 |
| competition_agent_excluded_themes (신규) | agent_id FK, theme_position, theme | 제외 테마 순서 저장 |

`risk_level`은 1=안정형, 2=안정추구형, 3=위험중립형, 4=성장추구형, 5=성장형이다. DB에도 1~5 CHECK를 둔다. 테마는 VARCHAR(255), 추가 요청은 LONGTEXT다. API는 추가 요청을 4000자로 제한한다.

**ai_settings_complete 컬럼은 추가하지 않는다.** 생성과 전략 저장을 한 트랜잭션으로 합쳤기 때문에 필요 없다. 이전 분리 설계의 컬럼을 수동 적용한 환경이 있다면 별도로 확인하고, 이 배포에서 임의로 삭제하지 않는다.

실행 DDL: [competition-ai-settings.sql](../db/competition-ai-settings.sql).
읽기 전용 사전 점검: [competition-ai-settings-preflight.sql](../db/competition-ai-settings-preflight.sql).

## 적용 순서

1. 운영 담당자가 대상 DB를 확인하고 preflight SQL과 `SHOW CREATE TABLE competitions`, `SHOW CREATE TABLE competition_participants`를 실행한다.
2. MySQL 버전, competition_type 저장값/타입, 부모 participant_id의 BIGINT 부호, 신규 테이블 존재 여부를 확인한다. 현재 DDL은 MySQL 8.4, signed BIGINT 부모 키를 기준으로 검증했다. 버전·타입이 다르면 정적 DDL을 그대로 실행하지 않는다.
3. 기존 대결/참가자 행 수와 USER_VS_AI 건수를 기록하고 복구 가능한 백업을 준비한다. ENUM MODIFY는 메타데이터 잠금을 기다릴 수 있으므로 장기 트랜잭션과 배포 시간대를 확인한다.
4. 앱 배포 **전에** DDL을 한 번 적용한다. 이 파일은 재실행용이 아니며 `IF NOT EXISTS`로 부분 실패를 숨기지 않는다.
5. 신규 3개 테이블과 UNIQUE/FK/CHECK, ENUM 확장 및 기존 행 수 보존을 확인한다.
6. BE 배포 후 기존 user_vs_ai 생성과 AI 2~4명 통합 생성/조회(DB 저장값)를 검증한다. FE는 참고용이며 이번 BE PR에는 수정이 없다.

회원 탈퇴 SQL도 신규 테마/전략 테이블을 참조한다. 따라서 **AI 생성 기능을 아직 사용하지 않더라도, 새 테이블을 먼저 만들지 않고 BE를 배포하면 회원 탈퇴가 실패할 수 있다.**

DDL은 MySQL에서 암묵적으로 커밋된다. 여러 문장을 애플리케이션 트랜잭션으로 감싸도 전체 DDL 롤백이 보장되지 않는다. 중간 실패 시 적용된 테이블/제약을 확인한 뒤 이어서 처리한다.

## 검증 결과 — 2026-11-03

운영 DB와 분리된 임시 MySQL Community Server **8.4.11**, loopback 포트 33379, 전용 데이터 폴더와 `stockspoon_ai_validation` DB에서 검증했다. 운영 계정/접속정보는 사용하지 않았다.

`CompetitionAiMySqlMigrationTests` **5개 통과**:

1. USER_VS_AI 대결·참가자가 있는 기존 형태로 복원한 뒤 실제 DDL 적용, 기존 데이터와 ENUM 보존.
2. 실제 마이그레이션 테이블에 JPA로 전략 4개·초기금·순서·한글/emoji 테마 저장 및 읽기.
3. risk_level=0 CHECK 위반 및 참가자 전략 중복 UNIQUE 위반 거절.
4. 마지막 참가자 저장 실패 시 대결·계좌·참가자·전략·테마 전체 롤백.
5. 탈퇴 bulk SQL이 테마 → 전략 → 참가자 순서로 삭제하고 다른 사용자의 기존 대결은 보존.

MySQL CHECK 위반 오류는 실제 드라이버에서 오류 번호 3819 / SQLState HY000으로 반환됐다. 테스트는 해당 DB 오류를 확인한다. 이 결과가 운영 DB의 현재 스키마나 부하에서의 DDL 잠금 시간까지 보장하지는 않는다.

## 재검증

테스트는 `AI_MYSQL_VALIDATION=true`일 때만 실행되며, 접속 주소가 `127.0.0.1:33379/stockspoon_ai_validation`로 고정되어 있다. 전용 검증 MySQL을 준비하고 해당 스키마를 비워 둔 다음 실행한다.

```powershell
$env:AI_MYSQL_VALIDATION = 'true'
.\gradlew.bat test --tests '*CompetitionAiMySqlMigrationTests'
Remove-Item Env:AI_MYSQL_VALIDATION
```

이 테스트는 전용 스키마를 Hibernate로 생성하고, 신규 전략 테이블을 제거한 뒤 운영 DDL을 적용하는 검증 fixture다. 일반 앱 DB를 대상으로 실행하는 도구가 아니다. 기본 전체 테스트에서는 실행하지 않는다.

## 롤백과 이번 범위

기존 데이터는 변경하지 않는 확장이지만, AI_VS_AI 행이 생긴 뒤 구버전 BE로 돌아가면 구버전 enum이 해당 행을 읽지 못한다. 롤백 전에 신규 유형 데이터의 처리 방식을 정해야 하며 테이블을 먼저 DROP하면 전략 데이터가 손실된다.

AI 간 대결은 이번 구현에서 READY로 유지한다. 실제 운용·성적·정산은 포함하지 않는다. FE가 현재 보내는 반대 위험도 매핑과 별도 ai-settings 요청은 FE 담당자가 통합 계약에 맞춰야 한다. 이번 작업에서 FE 변경은 모두 원복했으며 참고용으로만 사용한다.
