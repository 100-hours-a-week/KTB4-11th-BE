# 대결 AI 리포트 연동 계획

**목표:** 대결 종료 후 BE가 SQS로 리포트 생성을 요청하고, AI 서버가 생성한 결과를 BE API로 전달하면 저장하여 기존 리포트 조회 API에서 제공한다.

**범위:** ① AI 서버 전용 결과 수신 API ② 대결 종료 후 SQS 요청. 이 문서는 계획이며 구현·배포 완료를 뜻하지 않는다. 각 단계를 진행한 뒤 변경 파일과 코드 의미를 설명하고 다음 지시를 기다린다.

**기술:** 기존 Java/Spring MVC·JPA·AWS SQS·기존 인증 및 테스트 도구. 브랜치 생성·전환, 커밋·푸시, 운영 DB나 AWS 변경은 별도 사용자 지시 없이 하지 않는다.

## 1. 현재 코드에서 확인한 상태

- `CompetitionCompletionScheduler.completeExpiredCompetitions()`가 종료 후보를 찾아 `CompetitionCompletionService.completeCompetition(id)`를 호출한다. 로컬 코드의 스케줄러 기본 간격은 1시간이며 실제 환경 설정에 따라 달라질 수 있다.
- `CompetitionCompletionService`는 대결별 트랜잭션으로 최종 자산·승자를 확정하고 대기 주문을 취소한 후 완료 상태와 마지막 일별 성적을 기록한다. 현재 리포트 요청 SQS 전송은 없다.
- `CompetitionReportService.get(userId, competitionId)`는 저장된 시장 분석과 판단 요약을 읽는다. AI 호출이나 생성 결과 저장은 하지 않는다. 기존 FE 조회 URI는 `GET /api/v2/users/me/competitions/{competitionId}/report`다.
- `CompetitionMarketAnalysis`의 `date`는 DB 필수값이고 기존 조회 응답에 포함된다. AI가 전달한 news 예시에는 날짜가 없어 현재 구조에 그대로 저장할 수 없다. 수신일을 뉴스 날짜로 임의 저장하지 않는다.
- `CompetitionJudgmentSummary`는 제목과 내용을 저장한다. 기존 시장 분석·판단 요약은 목록 형태로 대결과 연결된다.
- 기존 SecurityConfig는 특정 API에 AI JWT의 actor=`AI`, subject=`ai-server`를 검사한다. 새 POST는 현재 별도 허용 규칙이 없으므로 인증과 인가·CSRF를 함께 검토해야 한다. 적용 전 AI 서버의 실제 토큰·호출 방식과 확인한다.
- 기존 주문 처리에 SQS 관련 코드가 있다. 리포트 연동의 설정·자격 증명·큐는 목적과 소비자가 다를 수 있으므로 무조건 같은 큐를 공유하지 않는다. 기존 패턴을 확인하고 필요한 부분만 사용한다.

## 2. AI 팀이 알려준 계약

### SQS 생성 요청

#### 전달받은 큐·인증 설정

| 항목 | 값 |
|---|---|
| AWS 리전 | `ap-northeast-2` |
| 리포트 요청 큐 | `stockspoon-v2-dev-report-request` (Standard Queue) |
| 큐 URL 환경변수 | `REPORT_QUEUE_URL` |
| 인증 | AWS SDK 기본 자격 증명 체인, EC2 IAM Role |
| 기존 주문 큐 | `stockspoon-v2-dev-order.fifo` (리포트 요청과 별도) |

`REPORT_QUEUE_URL`의 전달받은 값은 다음과 같다. 아래는 환경변수에 설정할 실제 URL이며, 설정 파일 문법에 따라 콜론 이스케이프 표기는 달라질 수 있다.

```text
https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-report-request
```

BE는 Report Queue에 리포트 생성 요청을 발행한다. 메시지에는 `eventId` 또는 `correlationId`를 포함한다. 같은 논리적 요청을 재전송할 때 동일한 식별자를 사용하고, Standard Queue 중복 전달을 고려해 중복 처리 방지 방식을 AI 서버와 맞춘다. SDK 기본 자격 증명 체인을 사용하며 Access Key와 Secret Key를 코드·설정 파일에 직접 저장하지 않는다.

```json
{
  "competition_id": 123,
  "participant_id": 456,
  "user_id": 789,
  "portfolio_reason_ids": ["550e8400-e29b-41d4-a716-446655440000"]
}
```

각 필드의 이름과 예시 타입을 보존한다. 위 JSON은 식별자 요구사항이 전달되기 전의 예시이므로 발행 시 `eventId` 또는 `correlationId`를 추가해야 한다. 어느 이름과 형식을 쓸지, `participant_id`의 대상과 `portfolio_reason_ids`의 의미·출처는 미확정이다. 빈 배열 허용 여부, 최대 크기와 메시지 버전도 AI 팀과 정한다.

### AI 서버의 결과 수신 요청

```http
POST /api/v1/competitions/{competition_id}/report
Content-Type: application/json
```

```json
{
  "news": [{"title": "카카오 데이터센터 화재", "summary": "서비스 장애"}],
  "thoughts": [{"title": "인플레이션", "text": "물가 상승이 투자 판단에 미친 영향..."}]
}
```

이 형식은 AI 팀이 알려준 예시다. 뉴스 날짜, 빈 배열, 필드 길이, 성공 응답과 오류 응답, 생성 실패 전달 방법, 재전송·재생성 식별자는 확정되지 않았다. 예시에 존재하지 않는 `date`나 `request_id`를 이미 확정된 필수 필드처럼 구현하지 않는다.

## 3. 선택한 방향과 확정할 사항

| 항목 | 현재 합의·권장안 | 구현 전 확인 |
|---|---|---|
| 생성 시점 | 실제 대결 종료·정산 성공 시 생성 요청 | READY/CANCELLED는 요청하지 않음. 실패 후 재정산 정책 확인 |
| 전송 안정성 | 완료 저장과 전송 대기 기록을 같은 DB 트랜잭션에 넣는 outbox 제안 | 테이블 추가·전송 주기·재시도/DLQ 운영 방식 승인 및 팀 협의 |
| 뉴스 날짜 | AI가 기사별 날짜를 보내는 방안 우선 제안 | 필드 추가 가능 여부. 불가능하면 null 저장/표시 등 FE·DB 정책 합의 |
| 저장 인증 | 기존 AI 전용 인증 규칙 재사용 검토 | AI의 인증 수단, 네트워크 경로, CSRF 적용 방식 확인 |
| 결과 재전송 | 동일 결과 재전송을 중복 저장 없이 처리 | 보고서 단위 ID 또는 경쟁별 1회/재생성/교체 정책 결정. 예시엔 request_id 없음 |
| SQS 데이터 | 위 네 필드를 갖는 메시지 | 참가자 유형, reason ID 수집 경로·빈 목록·데이터 시점 확인 |
| 결과 공개 | 기존 GET은 현재 상태 제한 없이 저장 내용을 반환 | 생성 중·실패·완료 표시 및 FE 공개 정책 확정 |
| 큐 운용 | `ap-northeast-2`의 Standard Report Queue, `REPORT_QUEUE_URL`, EC2 IAM Role | 역할의 실제 권한·visibility timeout·DLQ·AI 소비 성공 기준 |
| 메시지 식별자 | `eventId` 또는 `correlationId` 포함, 동일 요청 재전송 시 같은 값 유지 | 최종 필드명·형식, BE 중복 발행과 AI 중복 소비 처리 기준 |

## 4. API ① 결과 수신·저장 — 단계별 계획

### 4-1. 계약 확정·데이터 매핑

- [ ] 뉴스 날짜에 대한 AI·FE 결정 및 DB 변경 여부를 확정한다. AI가 날짜를 보낸다면 필드명·형식·필수 여부를 확인한다.
- [ ] `news[].title → CompetitionMarketAnalysis.title`, `news[].summary → content`, `thoughts[].title → CompetitionJudgmentSummary.title`, `thoughts[].text → content`를 적용한다.
- [ ] `competition_id` 존재 여부, 완료 상태 요구 여부, 빈 목록·null·문자 길이, 최초 저장·중복 재전송·재생성의 결과와 상태 코드를 합의한다.
- [ ] 결과 저장과 기존 GET의 응답 정책을 확인한다. 생성 중과 결과 없는 상태를 동일하게 보일지 여부를 결정한다.

### 4-2. 인증·요청 수신

- [ ] `competition/controller`에 AI 전용 POST 컨트롤러를 추가하고 `/api/v1/competitions/{competitionId}/report`에서 경로 ID와 `@Valid @RequestBody` DTO를 받는다.
- [ ] `competition/dto`에 class + Lombok `@Getter` 방식의 요청 DTO를 만든다. news/thoughts 중첩 항목의 허용 값은 4-1의 합의대로 검증한다.
- [ ] `SecurityConfig`에 해당 POST의 AI 전용 인증·인가를 추가한다. 사용자 JWT, 무인증, AI JWT, CSRF/서버 간 호출 경계를 테스트한다. 전역 보안을 완화하지 않는다.
- [ ] DTO 검증 실패, JSON 오류, 인증·인가 오류, 존재하지 않는 대결을 프로젝트의 기존 예외 규칙과 합의한 API 계약에 맞춰 반환한다.

### 4-3. 서비스·DB 저장

- [ ] `competition/service`에 결과 저장 메서드를 작성한다. 대결을 확인하고 news/thoughts를 각각의 Entity로 변환한다.
- [ ] 두 목록을 같은 DB 트랜잭션에서 저장한다. 일부 저장만 성공하는 일이 없도록 검증한다.
- [ ] 합의한 중복·재생성 정책에 필요한 잠금·DB 제약·식별자를 적용한다. 동시 재전송 테스트를 작성하고, 응답 성공 후 재시도해도 중복이 없음을 검증한다.
- [ ] 기존 GET 응답에 저장한 결과가 정상 반영되는지 확인한다. 날짜 요구에 따른 Entity·Repository·별도 DDL이 필요하면 마이그레이션을 작성하되 운영 DB에 실행하지 않는다.
- [ ] 성공 코드·응답 본문, 오류 코드, 접근 제한, 필드 길이와 정렬을 테스트·문서화한다.

예상 호출 흐름:

```text
AI 서버 → POST /api/v1/competitions/{competition_id}/report
 → AI 전용 인증·인가 → Controller(path ID, @Valid request)
 → 결과 저장 Service(competitionId, request)
   → 대결·재전송 정책 확인 → Entity 변환 → 하나의 트랜잭션으로 저장
 → 합의한 성공 응답

FE → GET /api/v2/users/me/competitions/{competitionId}/report
 → 기존 CompetitionReportService.get(userId, competitionId)
 → 저장된 시장 분석·판단 요약 반환
```

## 5. SQS ② 종료 후 생성 요청 — 단계별 계획

### 5-1. 요청 데이터 출처 확인

현재 브랜치 코드에서 확인한 내용:

- `competition_id`는 `Competition.id`, `user_id`는 대결의 `Competition.user.id`에서 얻을 수 있다.
- `CompetitionCompletionService.completeCompetition()`은 대결에 연결된 참가자 둘을 조회하고, 각 계좌의 `aiManaged` 값으로 사용자와 AI 참가자를 구분한다. `CompetitionParticipant.id`는 각자의 참가 ID다. 메시지에 어느 쪽을 넣어야 하는지는 AI 계약 확인이 필요하다.
- 현재 BE의 `AiOrderReport.id`는 Long 타입이고 `ReportReasoning`은 label/body로 구성된 임베디드 값이다. 이번에 검색한 BE 소스·DB 스크립트에서는 요청 예시의 UUID `portfolio_reason_ids`에 대응하는 필드나 수집 경로를 확인하지 못했다. 이는 저장소 검색 결과이며 AI 서버 내부 저장소의 존재 여부를 뜻하지 않는다.
- 동일 요청의 식별자로 `eventId` 또는 `correlationId`를 넣어야 하지만 최종 필드명과 AI 소비자의 중복 처리 기준은 아직 확정되지 않았다.

연결부 로그 계획 (구현 전 제안):

- 종료 처리에서 요청 대기 기록을 만들지 못하면 대결 ID·처리 단계·오류 유형을 남기고 기존 트랜잭션을 롤백한다. 정상적인 종료 조건 불충족은 오류로 기록하지 않는다.
- 전송 작업에서는 요청 ID·대결 ID·처리 단계·전송 시도 횟수와 성공/실패를 남기고, 실패 시 안전하게 기록 가능한 예외 유형·AWS 오류 코드·요청 ID 등 확인 가능한 정보를 기록한다. DB 성공 표시 전 SQS 응답이 불확실하면 실패 또는 성공으로 단정하지 않고 재시도·중복 가능성을 구분한다.
- 요청 전체 본문, 인증 정보, 리포트 내용은 로그에 넣지 않는다. 로그 필드와 식별자는 실제 코드·AI 계약에 맞춰 확정한다.

- [ ] 대결·참가자·사용자·AI 판단 근거 관련 Entity, Repository 및 실제 데이터의 관계를 확인하고 각 ID의 출처와 소유권을 확정한다.
- [ ] 포트폴리오 근거 ID가 없다면 빈 배열로 보낼지 실패로 처리할지 합의한다. 대결 시점에 고정된 데이터인지도 확인한다.
- [ ] AI 소비자와 메시지 식별자(`eventId` 또는 `correlationId`)·전송 성공 의미·중복 처리 기준을 확정한다. 리전·큐 이름·환경변수·인증 방식은 위 전달받은 설정을 따른다.

### 5-2. 완료와 전송 대기 기록을 함께 저장

- [ ] 사용자 승인 후 대결별 전송 대기 outbox Entity·Repository·DDL을 추가한다. 최소 정보는 경쟁 식별자, 요청 식별자/종류, 전송 상태와 재시도 관리 정보다. 정확한 컬럼과 메시지 저장 방식은 합의된 계약에 맞춰 결정한다.
- [ ] `CompetitionCompletionService.completeCompetition()`에서 실제 완료 성공 시에만 outbox를 기록한다. 완료 상태·정산·마지막 일별 성적·outbox는 같은 트랜잭션에서 커밋하거나 함께 롤백한다.
- [ ] 종료 후보가 반복 조회되거나 여러 BE 인스턴스가 실행해도 동일 목적의 요청 기록이 중복 생성되지 않도록 잠금·유일 제약을 설계하고 테스트한다. 재생성은 최초 생성과 별도 정책으로 다룬다.
- [ ] 기존 완료 대결을 소급 요청할지는 별도로 결정한다. 배포만으로 모든 과거 대결을 자동 재처리하지 않는다.

### 5-3. 전송·재시도

- [ ] `competition` 전용 SQS 요청 DTO·직렬화 및 전송 서비스를 추가한다. `REPORT_QUEUE_URL`과 AWS SDK 기본 자격 증명 체인을 사용하고, EC2에서는 IAM Role로 인증한다. 기존 주문 DTO/서비스를 결합하지 않는다.
- [ ] 전송 작업은 커밋된 미전송 outbox만 읽는다. 실제 SQS 성공 응답을 확인한 후 전송 완료로 기록하며, 실패는 재시도 대상으로 남긴다.
- [ ] 다중 인스턴스의 동시 전송 제어, 재시도 간격·최대 횟수, 영구 실패 알림과 재처리를 설계한다. SQS 전송 성공 뒤 DB 성공 기록 전 장애로 인한 중복 전송 가능성을 AI 소비자와 함께 처리한다.
- [ ] 전송 실패가 완료 정산을 되돌리거나 다른 대결의 전송을 막지 않도록 경계를 정한다. 운영 큐 설정·생성은 인프라 담당과 조율하고 임의로 변경하지 않는다.

예상 호출 흐름 — 아래는 제안이며 아직 존재하는 코드가 아니다:

```text
CompetitionCompletionScheduler.completeExpiredCompetitions()
 → CompetitionCompletionService.completeCompetition(id)
   → 종료 시각·상태 확인 → 정산·COMPLETED·일별 성적
   → 리포트 요청 outbox 추가 → DB 커밋
 → 별도 전송 작업이 미전송 outbox 조회
 → 대결 리포트 SQS publisher → AI 서버 소비
```

## 6. 테스트·문서·완료 기준

- [ ] 정상 결과 저장 후 기존 GET에서 날짜·제목·본문이 정확히 반환된다.
- [ ] 인증·인가, 유효하지 않은 요청, 없는 대결, 미완료 대결의 처리, 빈 결과 정책을 합의한 계약대로 검증한다.
- [ ] 동일 결과 재전송, 동시 요청, DB 실패 시 전체 롤백과 재생성 정책을 검증한다.
- [ ] 실제 종료에서만 outbox가 생기고, 정산 실패·롤백에는 생기지 않는다. 중복 스케줄 실행과 다중 인스턴스 경합도 검증한다.
- [ ] SQS 성공·실패·일시 장애·재시도·중복 전송을 실제 외부 AWS에 영향을 주지 않는 테스트 환경에서 검증한다. 메시지 JSON과 AI 계약을 대조한다.
- [ ] 기존 대결 생성·종료·일별 성적·리포트 조회 및 보안 테스트와 프로젝트 정적 검사를 실행한다. 실제 AI 연동 검증은 계약·연결 준비 후 별도로 수행한다.
- [ ] `docs/competition-creation.md`를 실제 구현된 Controller → Service → Repository·전송 흐름과 자연어 설명으로 갱신한다. 확정되지 않은 정책과 실제 동작을 구별한다.
- [ ] AI 팀의 뉴스 날짜·필드·결과 응답·인증·중복 규칙과 SQS 데이터 출처·큐 설정을 확정한다. DB migration, 보안 설정, 큐 구성의 운영 반영 절차를 관련 담당자와 조율한다.

## 7. 진행 순서

먼저 AI 팀의 응답과 기존 코드에서 확인 가능한 ID 관계를 확인한다. 그다음 결과 수신 API를 단계별로 구현하고 조회 연결을 검증한다. 이어서 종료·outbox·SQS 전송을 단계별로 구현한다. 각 단계마다 파일·코드 역할·검증을 설명하고 사용자의 다음 진행 지시를 기다린다.
