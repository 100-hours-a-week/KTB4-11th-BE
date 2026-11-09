# DB 전환: BE SQL Producer와 Lambda Consumer

2026-10-25, `feat/migration-DB`.

BE는 실패한 트랜잭션의 SQL/바인딩을 SQS에 전달하는 Producer다.
Lambda가 SQS를 읽고 SQL을 재생한다. BE에 이 migration 큐의 Consumer를 추가하지 않는다.
기존 주문 업무 큐(`order.messaging`)는 별도 기능이다.

## 실제 수정 동작

`BUSINESS_COMMAND` 전송과 HTTP 본문 캡처를 제거했다. 이관 큐에는 기존 Lambda 계약인
`schemaVersion=2`, `operation=SQL_TRANSACTION`, `statements`만 보낸다.

| 장애 단계 | BE 동작 | SQS |
|---|---|---|
| INSERT/UPDATE/DELETE 전 SELECT 또는 트랜잭션 시작에서 연결 실패 | 쓰기 시도가 없는 요청만 새 서비스 트랜잭션으로 제한 재시도 | 보낼 DML이 없으므로 적재하지 않음 |
| 재시도 후 정상 쓰기 | 기존 동기 성공 응답 | 적재 없음 |
| DML 시도 + 롤백 확인 + SQL/바인딩 재생 가능 | SQL_TRANSACTION 적재 성공 후 202 | Lambda 실행 대기 |
| DML 시도 + 롤백/커밋 결과 미확인 | HTTP 실패로 전파하고 식별 로그 기록 | 임의 SQL 재생 차단 |
| SQL 없는 실패가 재시도 한도를 넘음 | 마지막 실패 전파 | 빈 SQL/HTTP 요청을 대신 적재하지 않음 |

SQL 실행 직전에 변경 시도를 기록해 DML의 응답 유실을 pre-DML로 오인하지 않게 한다.
CALL/CTE/DDL/다중 문장/알 수 없는 SQL도 보수적으로 변경 시도로 취급한다.
같은 요청의 다른 트랜잭션에서 변경을 시도했거나 추적 밖 쓰기, 진행 중 트랜잭션,
커밋 미확정이 있으면 전체 컨트롤러 재시도는 차단한다. 이전 쓰기의 롤백이 확인돼도 이 재시도에는 포함하지 않는다.

## SQL 생성 전 재시도

`@DbWritePreDmlRetryable`은 명시한 계좌 생성/이름 변경, 관심종목 추가/삭제, 주문 생성/취소
핸들러에만 적용한다. 컨트롤러 경계에서 이미 변환한 인자로 다시 호출하므로 HTTP 요청을 새로 보내거나
본문을 다시 읽지 않는다. 서비스 트랜잭션이 종료된 뒤 재호출하며, 앞선 실패 추적은 안전 조건 확인 후
초기화하고 HTTP request ID와 부하테스트 write ID는 유지한다.

일시적 연결 장애(예: 08S01, Connection is closed, ProxySQL 9001)가 대상이다.
read-only 1290 DML 오류는 전체 핸들러 재시도 대상이 아니며 기존 SQL 큐잉 조건을 사용한다.
롤백 오류가 원래 SELECT 통신 오류를 덮어쓴 경우에는 DataSource에서 관측한 원인을 사용한다.
업무 검증 예외를 이전 SQL 오류 때문에 재시도하지 않는다.

| 환경변수 | 기본값 |
|---|---|
| DB_WRITE_REPLAY_ENABLED | 기존 ECS 설정. true일 때 추적/SQL Producer/pre-DML retry 활성화 |
| DB_WRITE_PRE_DML_RETRY_ENABLED | true |
| DB_WRITE_PRE_DML_RETRY_DELAY | 2s |
| DB_WRITE_PRE_DML_RETRY_MAX_RETRIES | 2, 최초 시도 외 추가 재시도 횟수 |

기본 대기는 최대 4초이고 SQL/서비스 실행 시간은 별도로 더해진다. 응답 제한과 장애 길이에 맞춰 조정한다.
모든 DB 접근은 추적 DataSource를 사용해야 한다. 허용 핸들러는 SELECT에서 DB를 변경하는 저장 함수를
사용하거나, DML 전 단계에서 취소할 수 없는 외부 작업을 실행해서는 안 된다. SELECT라는 첫 단어만으로
저장 함수 부수 효과를 판별할 수 없으므로 이 조건은 핸들러 검토 사항이다.
조회만 하다가 커밋한 앞선 트랜잭션은 허용하지만 종목마스터 backfill 등 실제 변경을 시도했다면 재시도하지 않는다.

## SQL 메시지와 중복 방지

메시지는 기존 `requestId`(replay UUID), `transactionId`, `httpRequestId`, 선택적 `writeId`,
SQL과 JDBC 바인딩 목록을 유지한다. 같은 트랜잭션에서 SQS 전송 결과를 확인하지 못해 resolver가
재실행돼도 같은 request ID/deduplication ID를 사용한다. SQS 수락을 확인한 뒤에만 202를 반환한다.

트랜잭션 내 앞선 DML이 성공했다가 나중 오류로 전체 롤백됐다면 앞선 DML도 함께 재생해야 한다.
현재 캡처는 실행을 **시도한** DML만 확보한다. 실패 이후 아직 생성되지 않은 SQL을 추론하거나
복잡한 업무 흐름의 나머지를 실행하지 않는다. 여러 쓰기/자동 생성 ID 의존 작업은 완결된 SQL 묶음을
만들 수 있는지 별도 검토해야 하고, 기존 생성 키 의존성 재생 차단도 유지한다.

현재 Lambda의 `db_write_replay_receipts`는 **같은 replay 메시지**의 재수신을 중복 처리하지 않게 한다.
원래 BE 동기 쓰기가 커밋됐는지 불확실한 문제까지 해결하지는 못한다. 이를 자동 재처리하려면:

1. 원래 요청부터 안정적인 client Idempotency-Key를 사용한다. HTTP request ID와 부하테스트 write ID는
   업무 고유키가 아니다. actor/업무 종류/대상 ID/정규화 payload의 SHA-256도 함께 검사한다.
2. 원래 BE 쓰기와 처리 영수증을 같은 DB 트랜잭션에서 커밋한다. Lambda도 같은 키의 영수증을
   확인/선점하고 SQL 변경과 영수증을 함께 커밋해야 한다. 같은 키·같은 payload는 이전 결과를 사용하고
   다른 payload는 충돌로 처리한다.
3. 이전 트랜잭션이 진행 중이면 고유키 INSERT 경합이 종료되기를 기다리거나 재시도한다.
   새 writer의 영수증 SELECT 한 번에서 행이 없다고 원래 실행이 실패했다고 단정하지 않는다.
   이전 writer fencing/진행 중 트랜잭션 정리/최종 GTID 적용도 확인해야 한다.
4. 이 계약을 원래 동기 경로와 Lambda 양쪽에 도입하기 전에는 DML 미확정 건의 차단을 풀지 않는다.
   기존 영수증 없는 5xx는 부하테스트 요청과 실제 DB 행을 대조해야 한다.

위 4개 항목은 후속 설계이며 이번 변경에서 새 영수증 스키마나 Lambda 구현을 추가하지 않았다.
SQS FIFO deduplication은 DB 업무 멱등성을 대체하지 않으며 전송 중복 억제 구간은
[AWS 문서의 5분 범위](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/FIFO-key-terms.html)다.

## Lambda 실행과 검증

기존 Lambda가 SQL_TRANSACTION만 처리하는 계약을 유지한다. BE는 이 큐를 consume/ack 하지 않는다.
Lambda는 영수증과 SQL의 커밋이 확인된 뒤 메시지를 삭제한다. 커밋 확인/삭제 실패로 재수신될 수 있으므로
동일 메시지 ID의 영수증을 사용한다. 자세한 전달 동작은
[AWS visibility timeout 문서](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-visibility-timeout.html)에 있다.

FIFO는 큐 안의 순서만 제공한다. 큐에 보관한 옛 이름 변경/관심종목 변경이 새 동기 쓰기 뒤에 실행되면
최신 상태를 덮을 수 있다. drain 중 신규 쓰기를 통제하거나 expectedVersion 같은 순서 검증이 필요하다.
현재 구조만으로 전체 HTTP 쓰기의 순서를 보장한다고 해석하면 안 된다.

회귀 테스트는 실제 H2/DataSource proxy/Spring 트랜잭션/AOP를 연결해 SELECT 응답과 롤백 연결이
함께 끊긴 뒤 새 연결로 한 번만 쓰는지, 재시도 중 DML read-only 실패가 SQL/바인딩으로 적재되는지 확인한다.
변경 시도/커밋 미확정/업무 거절/재시도 소진/전송 결과 유실도 검사한다. SQS는 모의 객체로 대체한다.

```bash
./gradlew test --tests '*DbWritePreDmlRetryTests' --tests '*DbWriteReplayTransactionScopeTests'
```

실제 전환에서는 `db_write_pre_dml_retry_scheduled/succeeded/exhausted/skipped`,
`db_write_replay_candidate/queued/skipped/enqueue_failed`를 request ID로 대조한다.
재시도로 정상 반영된 요청은 SQS에 쌓이지 않는 것이 정상이다. SQS NumberOfMessagesSent,
HTTP 202, Lambda 적용 영수증과 최종 DB 행까지 확인해야 SQL 보관과 복구를 구분할 수 있다.
