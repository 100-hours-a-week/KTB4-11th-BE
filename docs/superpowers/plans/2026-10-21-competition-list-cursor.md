# 대결 목록 cursor 페이지네이션 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 사용자 확인 없이 브랜치를 생성·전환하거나 커밋·푸시하지 않는다. 아래 제안 사항은 구현 전 사용자 검토를 받는다.

**Goal:** GET /api/v2/users/me/competitions를 cursor 방식으로 분할 조회해 FE 무한스크롤을 지원한다.

**Architecture:** 기존 createdAt DESC, id DESC 정렬을 유지한다. cursor 이후의 대결을 limit+1개 조회해 has_next를 판별하고 실제 반환하는 대결의 참가자·계좌만 읽어 기존 수익률 계산을 적용한다. 진행 중 개수는 페이지와 무관하게 전체 대결에서 집계한다.

**Tech Stack:** 기존 Spring MVC, Spring Data JPA, Lombok class DTO, Java Base64 URL encoder, 기존 Jackson 및 테스트 도구. 새 라이브러리는 추가하지 않는다.

**Spec:** 이 대화의 무한스크롤 + cursor 결정 및 아래 API 계약 제안. 기존 처리 흐름은 docs/competition-creation.md의 목록 조회 항목을 참고한다.

## 확정 사항과 구현 제안

- 확정: 무한스크롤 + cursor 사용, URI 유지, 기존 카드 항목과 수익률·winner·d_day 정책 유지.
- 최종 정책: cursor·limit 모두 생략하면 전체 조회한다. 분할 조회의 limit 허용 범위는 1~100이고, cursor만 있으면 기본 10이다. page 파라미터는 사용하지 않는다.
- 제안: state 생략 시 전체, 지정 시 ready / in_progress / completed / cancelled 중 하나로 필터링한다. UI 탭에서 어떤 상태들을 묶을지는 별도 FE 정책이며 임의로 그룹화하지 않는다. 필터 자체가 필요하지 않다면 구현 전 이 항목과 관련 테스트를 제외한다.
- 제안: 상단 최근 카드 최대 3개는 FE가 최초 전체 조회(limit=10)에서 선택해 보관하고, 하단 필터·추가 페이지 응답으로 덮어쓰지 않는다. 상단이 진행 중 대결만 표시해야 한다면 별도의 최신 진행 중 3개 조회 정책을 먼저 정해야 하며 현재 안은 전체 최근 3개 기준이다.
- 응답에 새 recent_competitions 배열이나 별도 상단 카드 API는 이번 안에 추가하지 않는다.

## Global Constraints

- 현재 확인 브랜치: feat/171-competition-list-pagination. 확인 당시 미커밋 변경 없음. 실행 시 다시 확인하며 자동 브랜치 전환 금지.
- AI 수익률 공개·종료 정산·일별 저장 로직은 이번 변경에서 수정하지 않는다.
- API 응답의 competitions 항목 구조는 유지한다. 사용자 후속 결정에 따라 cursor와 limit을 모두 생략하면 전체 반환하고 pagination을 생략한다. state만 지정하면 해당 상태 전체를 반환한다. cursor 또는 limit을 지정할 때만 분할 조회하며 cursor만 있으면 10개씩 조회한다.
- 사용자 ID는 JWT에서 얻고 모든 페이지 쿼리에 소유자 조건을 적용한다. cursor는 인증 수단이 아니다.
- 전체 목록을 먼저 읽고 Java에서 잘라내지 않는다. lookahead 1건은 has_next 판별에만 쓰고 수익률을 계산하지 않는다.
- CloudWatch·CI·배포 설정 변경과 운영 DB 실행은 하지 않는다.

## API 계약 제안

~~~http
GET /api/v2/users/me/competitions?limit=10
GET /api/v2/users/me/competitions?limit=10&cursor=<next_cursor>
GET /api/v2/users/me/competitions?limit=10&state=in_progress
~~~

~~~json
{
  "ongoing_competition_count": 12,
  "competitions": [],
  "pagination": {
    "limit": 10,
    "has_next": false,
    "next_cursor": null
  }
}
~~~

- competitions는 해당 페이지의 기존 CompetitionItem 목록이다.
- ongoing_competition_count는 선택한 필터·cursor와 무관하게 본인의 IN_PROGRESS 전체 개수이며 READY는 제외한다. 빈 페이지라고 0을 반환하지 않는다.
- next_cursor는 다음 페이지가 있을 때 마지막으로 반환한 대결의 DB createdAt·id에서 생성한다. 마지막 페이지는 명시적인 null이다.
- 전체 건수·전체 페이지 수·page는 반환하지 않는다.

## 커서·조회 규칙

1. CompetitionListCursor는 createdAt(Instant), competitionId(long), 조회 상태 조건을 담는 작은 대결 전용 타입으로 둔다. 기존 order 서비스·DTO를 호출하지 않는다.
2. 커서는 Base64 URL-safe 문자열로 인코딩한다. 직렬화는 기존 Jackson 도구를 사용한다. FE는 내용 해석 없이 next_cursor를 전달한다.
3. 서버는 문자열 길이 상한 512, Base64·데이터 형식, 유효한 날짜, 양수 ID, 필터 일치를 검사한다. 잘못된 커서는 400 INVALID_COMPETITION_CURSOR로 반환한다. 원문 커서나 파싱 예외 메시지는 로그에 남기지 않는다.
4. limit 범위 오류는 400 INVALID_COMPETITION_LIMIT, state 오류는 400 INVALID_COMPETITION_STATE다. 숫자가 아닌 limit은 Spring MVC에서 400으로 처리한다. page는 지원하지 않으며 다른 미지정 쿼리처럼 무시한다.
5. 다음 범위를 limit+1개 읽는다. 첫 요청은 cursor 조건을 생략한다.

~~~sql
WHERE user_id = :userId
  AND <필요 시 상태 조건>
  AND (created_at < :cursorCreatedAt
       OR (created_at = :cursorCreatedAt AND competition_id < :cursorId))
ORDER BY created_at DESC, competition_id DESC
LIMIT :limitPlusOne
~~~

6. cursor는 실제 읽은 DB 시각 정밀도를 그대로 보존한다. 현재 시각이나 화면에 표시하는 날짜로 대체하지 않는다.
7. 조회된 수가 limit 초과면 has_next=true이며 초과분은 버린다. 마지막 반환 행으로 cursor를 만든다. 결과가 없거나 limit 이하이면 next_cursor=null이다.
8. cursor가 가리키는 행이 사라져도 저장된 정렬 키로 이어 읽을 수 있다. 삭제된 cursor 행을 반드시 조회해야 하는 구조로 만들지 않는다.
9. 새 대결은 첫 페이지에서 보도록 새로고침한다. cursor는 상태 변경까지 동결하는 스냅샷이 아니므로, 이미 지나간 정렬 위치의 대결이 필터에 새로 들어오는 경우 새로고침이 필요할 수 있다.

## 파일별 변경 범위

| 파일 | 변경 |
|---|---|
| competition/controller/CompetitionController.java | listCompetitions에서 cursor·limit·state를 받아 서비스로 전달, 잘못된 쿼리 응답 처리 |
| competition/service/CompetitionService.java | cursor 페이지 조회·개수 집계·해당 페이지 수익률 계산·응답 구성 |
| competition/dto/CompetitionListResponse.java | class+Getter 방식으로 pagination 추가, next_cursor null 유지 |
| competition/dto/CompetitionListCursor.java (신규) | 정렬 키·상태 조건의 인코딩·디코딩·검증 |
| competition/repository/CompetitionRepository.java | cursor 조건 조회와 countByUserIdAndState 추가 |
| competition/repository/CompetitionParticipantRepository.java | 사용자 ID와 이번 페이지 competition IDs로 사용자 참가자·계좌 제한 조회 |
| db/competition-list-pagination.sql (신규) | 기존 인덱스를 확인하고 (user_id, created_at, competition_id) 복합 인덱스 DDL 작성 |
| 관련 목록 테스트·신규 CompetitionListPaginationTests.java | API 계약·연속 조회·조회 범위 회귀 테스트 |
| docs/competition-creation.md | 기존 목록 조회 설명을 실제 호출 흐름·인자 전달·응답 예시로 갱신 |

인덱스는 새로운 업무 컬럼이 아니다. 기존 인덱스와 대상 DB 실행 계획을 확인할 수 없다면 적용 효과를 검증했다고 주장하지 않고 DDL 제안 상태로 남긴다. 상태 포함 인덱스를 추가로 무조건 생성하지 않는다.

## Review Focus

- 생성일 동률: ID를 보조 정렬 키로 사용해 중복·누락 없이 진행해야 한다.
- 빈 페이지: 전체 진행 중 개수가 초기화되지 않아야 한다.
- 필터·사용자 변경: 새 조회는 cursor를 초기화하고 소유자 조건으로 데이터가 격리되어야 한다.
- 추가 조회 범위: 참가자 전체 조회나 lookahead 수익률 계산이 남아 있으면 분할 조회의 효과가 줄어든다.
- API 호환성: 기존 무파라미터 전체 반환은 유지한다. FE 무한스크롤은 첫 요청부터 limit을 명시하고 상단 카드 유지 방식을 적용한다.

## Task 1: 조회 계약과 cursor 검증

아래 진행 기록은 당시의 중간 상태이며 현재 구현은 마지막의 후속 정책 변경 항목을 따른다. 첫 단계로 CompetitionListCursor와 단위 테스트만 작성했으며 cursor 관련 17개 테스트가 통과했다. 당시 Controller·Service·Repository·목록 응답 연결 및 limit 검증은 아직 작업하지 않았다.

두 번째 단계: CompetitionListResponse에 Pagination(limit, hasNext, nextCursor)을 추가했다. 기존 2인자 생성자는 pagination=null로 유지하며 이때 JSON에서 pagination을 생략한다. 페이지 처리 연결 시 서비스는 3인자 생성자로 Pagination을 전달한다. 마지막 페이지는 pagination 내부의 next_cursor=null을 명시한다. Controller·Service·Repository 연결은 아직 진행하지 않았다.

- [x] 기존 목록 HTTP 테스트와 호출자·문서·테스트 fixture를 찾고 새 시그니처로 바뀔 범위를 확인한다.
- [x] cursor 없음·인코딩 왕복·동률 정렬 키·형식 오류·필터 불일치·limit 경계 테스트를 먼저 작성한다.
- [x] 해당 테스트를 실행해 미구현으로 실패하는 것을 확인한다.
- [x] DTO와 cursor 타입, 컨트롤러 입력·예외 처리를 기존 응답 규칙에 맞춰 구현한다.
- [x] 테스트를 실행해 정상 응답과 400 오류 응답을 확인한다.

## Task 2: DB 페이지 조회와 서비스 연결

- [x] 0건·1건·정확히 limit건·limit+1건·여러 페이지·같은 createdAt·다른 소유자·상태 필터를 실제 DB fixture로 검증하는 테스트를 먼저 작성하고 실패를 확인한다.
- [x] Repository에 정렬 키 조건과 limit+1 조회를 추가한다. Pageable을 쓴다면 page=0의 setMaxResults 용도로만 사용하고 offset 페이지 이동은 사용하지 않는다.
- [x] 참가자 조회는 사용자 ID + 응답에 포함할 competition IDs로 제한한다.
- [x] 기존 항목 변환·수익률 계산을 해당 페이지에 적용한다. 전체 목록 복사나 불필요한 서비스 추상화는 추가하지 않는다.
- [x] 진행 중 개수는 별도 count 쿼리로 계산한다. 빈 페이지에서도 유지한다.
- [x] 첫 페이지 조회 후 새 대결 삽입·일부 삭제가 있어도 기존 다음 항목이 중복 없이 이어지는지 검증한다.
- [x] 반환 페이지 밖 참가자·계좌가 조회·평가 대상에 들어가지 않는지 확인한다.
- [x] 쿼리를 지원하는 인덱스가 없으면 전용 DDL을 작성한다. 운영 DB에는 실행하지 않는다.

## Task 3: FE 연동 흐름·회귀·문서

- [x] 첫 요청 → next_cursor 후속 요청 → 마지막 페이지 순서를 실제 HTTP 또는 기존 MockMvc 통합 방식으로 검증한다.
- [x] 요청 실패는 같은 cursor로 재시도하고, FE는 성공 응답 이후에만 next_cursor를 갱신하도록 문서화한다. 로딩 중 중복 요청과 stale 필터 응답은 FE가 제어한다.
- [x] 기존 대결 생성·목록 수익률·상세·결과 테스트를 새 응답 계약과 맞춰 검증한다.
- [x] 현 브랜치에 정의된 정적 검사를 수행한다. 추가 실패가 없다면 관련 없는 전체 재실행을 반복하지 않는다.
- [x] backend-call-flow-docs를 사용해 Controller의 jwt/cursor/limit/state 인자 → Service 검증·쿼리 → Repository 조건 → DTO 응답까지 자연어와 흐름도로 갱신한다.
- [x] 검증 범위와 API 호환성 변경, DB 인덱스 미적용 여부를 보고한다. 사용자가 요청하기 전 커밋·푸시하지 않는다.

## 실행 명령 예시

### 단계별 진행: Repository 조회 추가

- `CompetitionRepository.findCursorPage()`에 사용자·상태·생성 시각/ID 커서 조건과 최신순 정렬을 추가했다. Pageable은 page=0에서 조회 개수 제한에만 사용한다.
- `countByUserIdAndState()`로 페이지와 무관한 전체 진행 중 개수를 조회할 수 있게 했다.
- Repository 통합 테스트 3개와 기존 커서·응답 DTO 테스트 20개가 통과했다. 같은 시각의 ID 정렬, 사용자/상태 필터, 다음 페이지, 삽입·삭제 후 조회를 확인했다.
- Controller/Service는 아직 기존 전체 조회를 사용한다. 다음 단계에서 페이지 단위 참가자 조회와 서비스 연결을 진행한다.

~~~powershell
.\gradlew.bat test --offline --tests '*CompetitionListPaginationTests' --tests '*CompetitionCreationTests' --tests '*CompetitionDetailTests' --tests '*CompetitionResultTests'
.\gradlew.bat spotbugsMain --offline
~~~

클래스명과 테스트 범위는 실제 구현 시 존재하는 테스트를 확인해 조정한다. 각 단계는 실패 확인 → 최소 구현 → 성공 확인 순서로 진행한다.

## 최종 연결 결과

Controller·Service·Repository·응답 DTO 연결과 기존 목록 문서 갱신을 완료했다. 이전 단계의 호환용 2인자 응답 생성자와 전체 조회 Repository 메서드는 제거했다. 관련 57개 테스트와 spotbugsMain이 통과했다. 현재가 조회 중 트랜잭션을 유지하지 않도록 목록 Service 전체는 트랜잭션으로 묶지 않았다. 인덱스 DDL은 작성만 했으며 운영 MySQL 적용·실행 계획·부하 검증은 하지 않았다. 브랜치 변경·커밋·푸시는 하지 않았다.

## 후속 정책 변경: 생략 시 전체 반환

사용자 요청으로 cursor·limit 모두 생략 시 전체 반환을 유지한다. Controller limit은 Integer로 받는다. Service는 Pageable.unpaged() 또는 PageRequest.of(0, pageSize+1)를 선택하고, 전체 조회의 pagination은 null로 구성하여 JSON에서 생략한다. state만 지정해도 해당 상태 전체를 반환한다. 무한스크롤 첫 요청에는 limit을 명시한다. 이전 단계 기록의 기본 10개 정책보다 이 결정이 우선한다.
