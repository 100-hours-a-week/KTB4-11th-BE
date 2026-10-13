# 체결 Application 분리 및 DB 스키마 변경 인계

처음 읽는 사람은 [구현 흐름과 변경 영역 설명](execution-server-guide.md)을 먼저 참고한다.

## 적용 범위

같은 프로젝트에서 RiverBeApplication(BE)과 ExecutionApplication(체결)을 별도 프로세스로 실행한다. MySQL/Valkey를 공유하며 내부 HTTP로 통신한다. SQS는 이번 단계에 도입하지 않는다.

## 인프라 팀에 반드시 전달할 DB 변경

**새 테이블 1개: execution_requests. 기존 테이블의 컬럼/데이터 변경 없음.** SQL: [db/execution_requests.sql](../db/execution_requests.sql).

| 컬럼 | MySQL 타입 | NULL | 의미 |
| --- | --- | --- | --- |
| request_id | VARCHAR(36), ASCII/ascii_bin | 불가 | 내부 시장가 요청 UUID, PK |
| user_id | BIGINT | 불가 | 요청 사용자 ID |
| account_id | BIGINT | 불가 | 요청 계좌 ID |
| payload_hash | VARCHAR(64), ASCII/ascii_bin | 불가 | 입력 확인 SHA-256 해시 |
| order_id | BIGINT | 불가 | 생성된 주문 ID |
| created_at | DATETIME(6) | 불가 | 완료 기록 생성 시각 |

- 제약: request_id 기본키, order_id 고유키(uk_execution_requests_order).
- 외래키: fk_execution_requests_order, order_id → trade_orders(order_id). ON DELETE CASCADE 없음. 기록이 있는 주문 삭제는 FK에 의해 제한된다.
- user_id/account_id는 감사 및 입력 확인용 값이다. 별도 FK를 추가하지 않았다.
- 주문 생성/체결/자산 변경과 동일 트랜잭션으로 기록한다. 실패한 트랜잭션은 기록도 남기지 않는다.
- 실패/진행 상태 컬럼 없음: 저장된 행은 완료한 요청이다. 조회 결과 없음은 아직 완료가 확인되지 않았음을 뜻하며 확정 실패를 뜻하지 않는다.
- TTL/자동 삭제 없음. 과거 메시지 재전송에도 중복 처리를 막아야 하므로 삭제 정책은 별도 협의한다.
- 계좌 잠금으로 같은 계좌의 동시 요청을 직렬화하고 PK/고유키를 최종 방어로 사용한다.
- 가격/호가 준비는 계좌 잠금 트랜잭션 밖에서 한다. DB 쓰기와 요청 기록만 함께 커밋한다.

### 적용 순서와 롤백

1. 기존 trade_orders 테이블과 order_id 타입, 대상 스키마를 확인한다.
2. 서버 배포 전에 신규 SQL을 1회 적용한다. CREATE TABLE을 재실행하면 오류가 나므로 적용 이력을 관리한다.
3. JPA_DDL_AUTO=validate 또는 팀 운영 기준을 사용한다. 운영 자동 생성/update에 의존하지 않는다.
4. 체결 서버 시작 및 내부 인증 확인 후 BE를 전환한다. 기존 BE 체결 리스너가 실행되지 않는지 확인한다.
5. 코드 롤백 시 테이블/기록은 유지한다. 데이터가 있는 테이블을 DROP하지 않는다. 기록 없는 상태로 재처리하면 중복 체결 위험이 있다.

이 문서는 인계용으로 작성했으며 담당자에게 메시지를 보내거나 실제 배포 DB에 SQL을 실행하지 않았다.

## 빌드 및 시작점

```powershell
.\gradlew.bat bootJar executionBootJar
java -jar build/libs/river_be-0.0.1-SNAPSHOT.jar
java -jar build/libs/river_be-0.0.1-SNAPSHOT-execution.jar
```

- BE: RiverBeApplication, 시작 시 api 프로파일 활성화. 내부 체결 HTTP 클라이언트 사용. 체결 서비스/리스너 실행하지 않음.
- 체결: ExecutionApplication, 시작 시 execution 프로파일 활성화. 사용자 API/OAuth 구성 제외. 내부 체결 경로 외 접근 차단.
- 기존 @SpringBootTest는 main을 실행하지 않으므로 통합 테스트용 로컬 체결 구성을 유지한다. 운영 설정에 api/execution 프로파일을 혼합하지 않는다.
- Dockerfile은 BE JAR만, Dockerfile.execution은 체결 JAR만 복사한다. 로컬 기본 포트는 BE 8080 / 체결 8081. 서버 포트는 SERVER_PORT로 변경한다.

## 필요한 환경변수

| 대상 | 설정 |
| --- | --- |
| 공통 | SPRING_DATASOURCE_URL, MYSQL_USER, MYSQL_PASSWORD, JPA_DDL_AUTO |
| 공통 | 기존 REDIS_HOST/PORT/USERNAME/SSL/CLUSTER_MODE 및 IAM의 AWS_REGION/ELASTICACHE_CACHE_NAME |
| BE | EXECUTION_SERVER_URL=http(s)://체결서버:포트, EXECUTION_INTERNAL_SECRET |
| 체결 | EXECUTION_INTERNAL_SECRET (BE와 동일), SERVER_PORT |
| BE | 기존 Kakao/JWT 설정 |
| 시세 조회/평가 | 기존 KIWOOM_COLLECTOR_URL, KIWOOM_COLLECTOR_SECRET 및 Redis 시세 사용 설정 |

체결 프로파일은 KIWOOM_COLLECTOR_ENABLED를 false로 고정하고 시세 구독을 활성화한다. 체결 서버에 키움 app-key/app-secret은 필요하지 않다. AI 팀 시세 서버가 기존 Redis 규격 및 REST 조회 경계를 지원하는지는 별도 확인해야 한다. API 서버의 종목 구독 확보에도 KIWOOM_STREAM_ENABLED=true가 필요하다.

## 내부 통신 및 결과 확인

- 인증 헤더: X-Execution-Key. /internal/execution/market, /initial, /result만 체결 서버에 연결한다.
- 시장가 요청에는 UUID requestId, userId, accountId, order가 포함된다. 동일 ID·동일 입력은 기존 주문 ID를 반환한다. 같은 ID·다른 입력은 409 EXECUTION_REQUEST_CONFLICT.
- 연결 제한 3초, 응답 제한 15초. 연결 실패/응답 유실/HTTP 5xx 시 같은 요청 ID로 결과 조회만 수행하며 시장가 생성 요청을 자동 재전송하지 않는다.
- 결과 확인도 실패하면 503 EXECUTION_RESULT_UNKNOWN과 내부 요청 ID를 반환한다. 사용자는 체결 실패로 단정하여 새 주문을 재시도하면 안 된다. 외부 주문 API 자체의 재시도 중복 방지는 별도 정책이다.
- 지정가 최초 판단 실패는 접수된 주문을 보존한다. 체결 서버가 5초 주기로 PENDING 지정가를 재판단하며 만료/구독 관리는 기존 주기로 실행한다.
- health 경로는 현재 별도 허용하지 않는다. 인프라 헬스체크 방식 확정 시 인증 없는 liveness와 민감한 상태 정보를 구분하여 추가한다.

## 배포 전 남은 외부 검증

실제 MySQL 신규 SQL 적용, AWS Valkey ACL/IAM, 인프라 네트워크·비밀키 주입, AI 팀 시세 규격, 이미지 배포 및 헬스체크 대상은 인프라 환경에서 확인해야 한다. 실제 리소스 생성·SQL 적용·배포는 수행하지 않는다.

## 로컬 검증 결과 (2026-10-13)

- Gradle `build executionBootJar --build-cache` 성공, SpotBugs 통과.
- 테스트 301개: 280개 통과, 21개 건너뜀, 실패/오류 0개.
- 두 JAR의 Start-Class를 확인: RiverBeApplication / ExecutionApplication.
- 실제 BE/체결 JVM 2개, 공유 H2 파일 DB, 실제 내부 HTTP로 지정가/시장가 처리 확인. 현금 1,000,000 → 930,000 → 860,000, 보유 0 → 1 → 2, 체결 0 → 1 → 2. 시세·장중 시각은 테스트 fixture로 고정. 이 검증은 실제 AWS Redis/MySQL 연동 검증이 아니다.
- 같은 계좌 중복/동시 요청, 서로 다른 계좌의 같은 UUID 경쟁, 입력 불일치, 트랜잭션 롤백, HTTP504 후 결과 확인, 내부 비밀키/공개 API 차단, BE 체결 빈 미등록을 검증.
- 별도 코드 검토에서 발견된 계좌 잠금 중 외부 가격 조회, HTTP5xx 확정 실패 오판, assigned-ID merge 경쟁을 수정하고 재검증.
- 실제 MySQL SQL 적용, AWS 네트워크/ACL/IAM, 실제 Redis를 쓰는 두 Application 공동 실행, AI 시세 서버 규격 및 재시작/장애 운영 검증은 배포 환경에서 추가 확인 필요.
