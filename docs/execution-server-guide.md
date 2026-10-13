# 체결 서버 분리: 처음 읽는 사람을 위한 구현 설명

작성일: 2026-10-13

이 문서는 이번에 무엇을 바꿨는지, 주문이 어떤 경로로 처리되는지 설명한다. 코드를 처음 보는 사람은 1~6절부터 읽고, 구현 위치는 7절을 참고하면 된다. 인프라 팀에 전달할 정확한 DB 변경·설정은 [인프라 인계 문서](execution-server-infrastructure-handoff.md)를 참고한다.

## 1. 왜 분리했는가

기존 BE는 로그인·계좌·주문 API뿐 아니라, 실시간 시세를 받아 대기 주문의 체결 조건을 확인하고 체결까지 수행했다. 주문 수와 시세량이 늘면 체결 작업이 사용자 API와 같은 서버 자원을 사용한다.

이번에는 체결 판단·처리를 별도 서버에서 실행하도록 바꿨다. BE와 체결 서버를 따로 실행하고 확장할 수 있다. 같은 저장소와 공유 DB를 사용하며, 현금·보유 수량을 계산하는 기존 체결 규칙은 재사용한다.

여기서 체결은 이 프로젝트의 **모의 체결 처리**다. 키움에 실제 매수·매도 주문을 전송하는 기능을 추가한 것이 아니다.

## 2. 먼저 알아둘 말

| 용어 | 쉬운 뜻 |
| --- | --- |
| Application | 프로그램을 시작하는 Java 클래스 |
| 프로세스/JVM | 실행 중인 Java 프로그램 한 개. 같은 코드를 써도 각각 별도 메모리를 가진다. |
| JAR | Java 프로그램을 실행할 수 있도록 묶은 파일 |
| Bean | Spring이 만들어 연결하고 관리하는 객체 |
| 프로파일 | 어떤 설정·객체를 사용할지 구분하는 실행 설정 |
| 내부 HTTP | BE가 체결 서버에 보내는 서버 간 요청 |
| 트랜잭션 | 여러 DB 변경을 모두 함께 저장하거나 모두 취소하는 작업 단위 |
| 잠금 | 같은 계좌를 동시에 변경할 때 순서를 지키게 하는 장치 |
| 요청 ID | 같은 내부 요청인지 구분하는 고유 번호 |

## 3. 이전과 지금의 차이

이전에는 주문 컨트롤러가 같은 프로그램 안에 있는 체결 서비스를 직접 호출했다.

```text
BE 하나
  주문 접수 → 체결 서비스 호출 → DB 반영 → 응답
  Redis 시세 수신 → 대기 주문 판단 → DB 반영
```

지금은 실행 시작점이 두 개다.

```text
BE: RiverBeApplication
  주문 접수·취소·조회
  내부 HTTP로 체결 요청

체결 서버: ExecutionApplication
  내부 HTTP 요청 수신
  Redis 시세를 이용해 체결 판단·처리

공유 MySQL: 주문·계좌·보유·체결·보고서·내부 요청 기록
공유 Redis: 최신 시세·구독 수요·시세 사용 가능 상태
```

BE 시작점은 `api` 프로파일을 켜고, 체결 시작점은 `execution` 프로파일을 켠다. BE에는 체결 서비스/리스너가 등록되지 않는다. 체결 서버는 필요한 기능 구역만 탐색하여 로그인·계좌·주문 공개 API와 OAuth 설정을 불러오지 않는다.

같은 프로젝트이므로 각 JAR에 공통 클래스가 포함될 수 있다. **파일이 들어 있는 것과 그 기능이 실제로 실행되는 것은 다르다.** 실행할 객체를 구성 단계에서 구분한다.

## 4. 지정가 주문은 어떻게 흐르는가

예: 삼성전자 1주를 70,000원 이하에 매수한다.

1. BE가 AI 요청자 인증, 계좌 소유권, 입력값, 주문 시간·호가단위 등을 검사한다.
2. `OrderSubscriptionService`가 해당 종목의 시세가 필요하다는 임시 수요를 확보한다.
3. BE가 주문을 DB에 저장한다. 매수라면 필요한 현금을 예약한다. 아직 체결하지 않았다면 현금 잔고 자체를 차감하는 것이 아니다.
4. 주문 저장 트랜잭션이 끝난 뒤, BE가 체결 서버의 `/internal/execution/initial`에 사용자·계좌·주문 ID를 보낸다.
5. 체결 서버가 계좌와 주문을 다시 확인하고, 기존 최신 시세 경로로 최초 판단을 수행한다.
6. 조건이 맞으면 체결 서버가 계좌 잠금을 잡고 현금·보유 수량·주문 상태·체결 내역 등을 한 트랜잭션으로 반영한다. 조건이 맞지 않으면 주문은 `PENDING`으로 남는다.
7. BE가 공유 DB에서 결과를 읽어 기존 주문 응답 형식으로 반환한다.

그 뒤 가격이 바뀌면 체결 서버의 리스너가 Redis 시세 알림을 받아 대기 주문을 판단한다. BE가 매번 HTTP 요청을 보내는 것은 아니다.

### 최초 판단 요청이 실패하면

이미 저장한 주문은 유지한다. 내부 요청 실패를 로그에 남기고 BE는 DB의 주문 상태를 응답한다. 체결 서버의 `PendingExecutionRecovery`가 5초 간격으로 대기 지정가를 다시 판단하여, 최초 요청이나 시세 알림이 누락된 경우를 보완한다.

이 복구 작업은 체결 서버에서만 실행된다. 만료 주문 정리와 구독 목록 갱신은 기존 구독 관리 작업을 함께 사용한다. 복구 주기는 모든 주문이 정확히 5초 안에 체결된다는 보장이 아니다. DB·외부 조회·작업량에 따라 시간이 달라진다.

## 5. 시장가 주문은 어떻게 흐르는가

예: 현재 매도 호가를 이용해 삼성전자 1주를 바로 매수한다.

1. BE가 인증·입력·계좌·종목을 검사하고 구독 수요를 확보한다.
2. BE가 UUID 형식의 내부 요청 ID를 생성한다.
3. `/internal/execution/market`으로 요청 ID, 사용자·계좌 ID, 주문 입력을 보낸다.
4. 체결 서버가 입력과 기존 처리 기록을 확인한다.
5. 처음 받은 요청이라면 필요한 보유 종목 평가 가격과 최신 호가를 준비한다. 외부 가격 조회는 계좌 잠금을 잡기 전에 수행한다.
6. DB 트랜잭션에서 계좌를 잠그고, 같은 요청이 먼저 처리됐는지 다시 확인한다.
7. 기존 시장가 처리로 주문 생성·현금·보유·체결·보고서를 반영하고, 내부 요청 완료 기록도 함께 저장한다.
8. 체결 서버가 주문 ID를 반환하고, BE는 DB에서 주문 응답을 만들어 반환한다.

시장가의 주문 생성과 체결을 한 트랜잭션으로 처리하는 기존 규칙을 유지한다. 최신 호가의 기존 5초 기준과 시세 출처 유효성 검사도 유지한다. 호가 잔량이 부족한 경우 등의 기존 거부 규칙 역시 그대로다.

## 6. 왜 요청 기록 테이블을 만들었는가

체결 서버가 매수를 완료한 뒤 응답 전송만 실패할 수 있다. BE가 응답을 못 받았다는 이유만으로 같은 요청을 다시 실행하면 두 번 매수할 위험이 있다.

새 `execution_requests` 테이블은 다음 관계를 기억한다.

```text
내부 요청 ABC → 이미 처리한 주문 123
내부 요청 ABC 재전송 → 다시 매수하지 않고 주문 123 반환
```

### 저장하는 값

| 값 | 쓰임 |
| --- | --- |
| request_id | 내부 요청의 고유 번호. 중복 저장할 수 없는 기본키 |
| user_id / account_id | 어느 사용자·계좌의 요청인지 확인 |
| payload_hash | 같은 ID로 다른 주문 입력을 보냈는지 확인하는 SHA-256 값 |
| order_id | 실제로 생성한 주문 번호. 고유키이자 주문 테이블을 참조하는 외래키 |
| created_at | 완료 기록 생성 시각 |

입력 해시는 비교용이다. 주문 입력 원문 전체를 이 테이블에 다시 저장하지 않는다. 해시는 암호화한 원문을 복원하는 기능도 아니다.

이 테이블에는 **완료한 요청만 기록**한다. 처리 도중 오류가 발생하면 주문·자산 변경과 기록을 모두 롤백한다. 행이 없다는 사실만으로 확정 실패라고 판단할 수는 없다. 아직 진행 중일 수도 있다.

같은 계좌의 동시 요청은 계좌 잠금으로 순서를 정한다. 서로 다른 계좌가 우연히 같은 요청 ID를 사용하더라도, 완료 기록을 반드시 새 행으로 INSERT하여 기본키 충돌 시 두 번째 거래를 롤백한다. 기존 행을 갱신하는 `merge` 방식은 사용하지 않는다.

### 통신이 실패하면

연결 실패·응답 유실·HTTP 5xx가 발생하면 BE는 같은 요청 ID로 `/internal/execution/result`를 조회한다. 시장가 생성 요청을 자동으로 다시 보내지는 않는다.

- 완료 기록이 확인되면 기존 주문 ID로 응답한다.
- 결과 확인도 실패하면 `503 EXECUTION_RESULT_UNKNOWN`과 내부 요청 ID를 응답 메시지에 넣는다.
- 알려진 HTTP 4xx 거부는 거부 응답으로 전달한다.

`EXECUTION_RESULT_UNKNOWN`은 “매수되지 않았다”가 아니라 **“지금 결과를 확인할 수 없다”**는 뜻이다. 이때 새 주문을 자동 생성하면 중복 매수 위험이 있다.

이 기능은 내부 통신의 동일 요청에 대한 중복 방지다. AI나 사용자가 외부 주문 API를 새 요청으로 호출하면 새 내부 요청 ID가 만들어진다. 외부 주문 API 자체의 중복 방지는 별도 정책이다.

추후 SQS를 도입해도 요청 ID와 DB의 중복 확인 기능을 재사용할 수 있다. 현재 SQS 연결은 구현하지 않았다.

## 7. 어느 파일을 어떻게 바꿨는가

아래 링크의 소스 기준 경로는 `src/main/java/com/stock_spoon/river_be/`다.

| 영역 | 파일 | 변경 내용 |
| --- | --- | --- |
| BE 시작점 | [RiverBeApplication](../src/main/java/com/stock_spoon/river_be/RiverBeApplication.java) | api 프로파일 활성화. BE가 원격 체결 경로를 사용하도록 구분 |
| 체결 시작점 | [ExecutionApplication](../src/main/java/com/stock_spoon/river_be/execution/ExecutionApplication.java) | 신규 시작점. 필요한 체결/시세/주문 지원 객체 및 공유 JPA 구성 등록 |
| 주문 접수 | [OrderController](../src/main/java/com/stock_spoon/river_be/order/controller/OrderController.java) | 체결 서비스 직접 호출을 ExecutionGateway 호출로 변경 |
| 호출 경계 | [ExecutionGateway](../src/main/java/com/stock_spoon/river_be/order/client/ExecutionGateway.java) | 최초 지정가 판단과 시장가 처리의 공통 호출 계약 |
| BE 내부 HTTP | [RemoteExecutionGateway](../src/main/java/com/stock_spoon/river_be/order/client/RemoteExecutionGateway.java) | 체결 서버 호출, 비밀키 헤더, 제한 시간, 결과 확인 처리 |
| 기존 체결 연결 | [LocalExecutionGateway](../src/main/java/com/stock_spoon/river_be/execution/service/LocalExecutionGateway.java) | 기존 체결 기능 연결. 가격 준비와 자산 변경 단계를 구분 |
| 체결 내부 API | [InternalExecutionController](../src/main/java/com/stock_spoon/river_be/execution/controller/InternalExecutionController.java) | 시장가·최초 지정가·결과 조회 요청 수신, 입력 재검증 |
| 내부 인증 | [ExecutionSecurityConfiguration](../src/main/java/com/stock_spoon/river_be/execution/config/ExecutionSecurityConfiguration.java) | X-Execution-Key 인증, 중복 헤더 거부, 그 외 경로 차단 |
| 완료 기록 | [ExecutionRequest](../src/main/java/com/stock_spoon/river_be/execution/entity/ExecutionRequest.java), [저장소](../src/main/java/com/stock_spoon/river_be/execution/repository/ExecutionRequestRepository.java), [서비스](../src/main/java/com/stock_spoon/river_be/execution/service/ExecutionRequestService.java) | 신규 테이블 모델·조회·잠금·중복 검사·동일 트랜잭션 INSERT |
| 응답 조회 | [OrderQueryService](../src/main/java/com/stock_spoon/river_be/order/service/OrderQueryService.java) | 체결 리스너의 응답 생성 책임을 주문 영역으로 이동 |
| 체결 작업 | [OrderExecutionListener](../src/main/java/com/stock_spoon/river_be/execution/listener/OrderExecutionListener.java), [OrderExecutionService](../src/main/java/com/stock_spoon/river_be/execution/service/OrderExecutionService.java), [OrderBuyPriceService](../src/main/java/com/stock_spoon/river_be/execution/service/OrderBuyPriceService.java) | BE api 프로파일에서는 실행 객체로 등록하지 않도록 변경. 기존 계산 재사용 |
| 누락 복구 | [PendingExecutionRecovery](../src/main/java/com/stock_spoon/river_be/execution/service/PendingExecutionRecovery.java) | 대기 지정가 재판단. 주문마다 전체 구독 갱신을 반복하지 않도록 구분 |

`OrderSubscriptionService`와 공통 계좌·주문·보유 저장소는 계속 재사용한다. 실행 시작점을 나눴다고 공통 엔티티의 연관관계나 DB를 분리한 것은 아니다.

## 8. 실행 파일·설정·CI 변경

| 파일 | 변경 내용 |
| --- | --- |
| [build.gradle](../build.gradle) | bootJar/bootRun의 BE 시작점 명시. executionBootJar/executionBootRun 추가 |
| [Dockerfile](../Dockerfile) | 여러 JAR 중 정확한 BE JAR만 복사 |
| [Dockerfile.execution](../Dockerfile.execution) | 체결 JAR를 만드는 별도 Docker 빌드 파일 |
| [application.yaml](../src/main/resources/application.yaml) | 체결 서버 URL과 내부 비밀키 설정 추가 |
| [application-execution.yaml](../src/main/resources/application-execution.yaml) | 체결 서버 이름·기본 포트 8081·시세 구독 설정. 직접 키움 수집 비활성화 |
| [.env.execution.example](../.env.execution.example) | 체결 서버 환경변수 예시 |
| [ci-dev.yaml](../.github/workflows/ci-dev.yaml), [ci-main.yaml](../.github/workflows/ci-main.yaml) | 기존 검사에 체결 JAR 빌드도 추가 |

```powershell
.\gradlew.bat bootJar executionBootJar

# BE 실행
java -jar build/libs/river_be-0.0.1-SNAPSHOT.jar

# 체결 서버 실행
java -jar build/libs/river_be-0.0.1-SNAPSHOT-execution.jar
```

이 명령은 설정 없이 실행이 보장되는 예제가 아니다. 공통 MySQL/Redis 연결과 아래 내부 설정이 먼저 필요하다.

- BE: `EXECUTION_SERVER_URL`, `EXECUTION_INTERNAL_SECRET`, 기존 사용자 API 설정
- 체결 서버: 같은 `EXECUTION_INTERNAL_SECRET`, 필요 시 `SERVER_PORT`
- 공통: 공유 DB/Redis 연결, 현재 시세 규격에 필요한 설정

내부 통신은 연결 3초·응답 15초 제한을 사용한다. BE는 URL과 비밀키가 없으면 시작에 실패하고, 체결 서버도 비밀키가 없으면 시작에 실패한다. 두 프로파일을 한 실행에 섞어 사용하지 않는다.

기존 자동 테스트는 Application의 main 함수를 실행하지 않는다. 기본 프로파일에서는 같은 JVM에서 기존 통합 체결 경로를 테스트하고, api/execution 전용 테스트는 분리 구성을 검증한다.

## 9. DB 변경과 인프라 전달

**신규 테이블은 execution_requests 하나이며 기존 테이블의 컬럼 변경은 없다.** [적용 SQL](../db/execution_requests.sql)은 파일로만 제공했다. 실제 로컬/배포 MySQL에 적용하지 않았다.

인프라 팀은 서버 배포 전에 대상 DB와 기존 `trade_orders.order_id` 타입을 확인하고 SQL을 적용해야 한다. 서버 코드 롤백 시 완료 기록은 보존한다. 기록을 지우면 같은 요청을 다시 실행할 위험이 있다.

기록에는 자동 TTL/삭제를 두지 않았다. 또한 order_id 외래키 때문에 해당 기록이 참조하는 주문 삭제가 제한된다. 이는 인프라와 데이터 정리 정책에 함께 전달해야 한다.

정확한 타입·키·외래키·적용 순서·환경변수·롤백 주의점은 [인프라 인계 문서](execution-server-infrastructure-handoff.md)에 정리했다. 팀원에게 외부 메시지는 보내지 않았다.

## 10. 무엇을 검증했는가

2026-10-13 최종 `build executionBootJar --build-cache`와 SpotBugs가 통과했다. 자동 테스트는 301개 중 280개 통과, 21개 건너뜀, 실패/오류 0개다.

주요 검증:

- BE에는 체결 서비스/리스너가 없고, 체결 서버에는 공개 주문 API/OAuth 구성이 없음을 확인.
- 전용 키 누락·오류·중복 헤더 거부, 공개 API 접근 차단 확인.
- 같은 계좌의 재전송/동시 요청, 다른 계좌의 같은 요청 ID 경쟁, 입력 불일치 확인.
- 거래 중 오류 발생 시 자산·주문·요청 기록이 함께 롤백되는지 확인.
- HTTP 504 뒤 같은 요청 ID로 완료 결과 확인, 확인 실패 시 결과 미확정 응답 확인.
- 실제 별도 JVM 두 개와 공유 H2 파일 DB, 내부 HTTP로 지정가·시장가 처리 확인. 현금은 1,000,000 → 930,000 → 860,000, 보유 수량은 0 → 1 → 2, 체결 건수는 0 → 1 → 2로 검증.

H2는 테스트용 DB이며 위 두 프로세스 테스트의 시세·장중 시각은 고정된 fixture를 사용한다. 따라서 실제 MySQL의 SQL 적용과 실제 Valkey/키움/AI 시세 서버 연결 검증을 대신하지 않는다.

## 11. 아직 남은 사항

- 실제 MySQL에서 신규 SQL·잠금·경쟁 동작 검증.
- 두 Application을 실제 공유 Redis/Valkey에 연결한 실행 검증.
- AI 팀 시세 서버의 키·채널·JSON·유효성·구독 수요·REST 조회 경계 확정 및 연동.
- 인프라 네트워크·비밀키·IAM/ACL·이미지 배포·헬스체크 방식 확인.
- 재시작·장애·복구 지연에 대한 운영 환경 검증.

코드 구현·로컬 검증과 실제 배포는 구분한다. 이 문서 작성 시점에는 배포 DB 적용·인프라 팀 메시지 발송·배포를 수행하지 않았다. SQS 연결과 외부 주문 API의 요청 중복 정책도 이번 구현 범위에 포함하지 않았다.
