> 2026-10-18: 각 BE의 시세 수신은 Redis 원본 채널 직접 구독으로 전환했습니다. 아래 담당 시세 중계 설명은 이전 구조이며, 최신 동작·권한·배포 순서는 [AI 시세 연동](ai-quotes.md)을 따릅니다. 기존 REST 담당은 유지합니다.

> 현재 AI 연동의 키·JSON·시각·체결 정책은 [AI Valkey 호환](ai-quotes.md)을 우선합니다. 아래 직접 WebSocket 설명은 단독 키움 검증 경로입니다.

# 로컬 개발용 Valkey

Stock Spoon 개발용 저장소입니다. 기존 6379 포트와 충돌하지 않도록 PC에서는 6380 포트를 사용합니다.

## 실행

`river_be` 디렉터리에서 실행합니다.

```powershell
docker compose -f compose.valkey.yaml up -d --wait
docker compose -f compose.valkey.yaml exec -T valkey valkey-cli ping
```

BE를 PC에서 실행하면 기본 설정으로 `localhost:6380`에 연결합니다. Spring Data Redis 의존성과 연결 설정이 추가되어 있습니다. 현재가·호가는 Redis에 저장하며, REST 조회는 고정 담당 BE로 모읍니다.

## BE 연결 설정

다음 환경변수 또는 기존 `.env.local` 속성 파일로 연결을 변경합니다. 인증 정보는 저장소에 커밋하지 않습니다.

| 변수 | 기본값 | 용도 |
|---|---|---|
| `REDIS_HOST` | `localhost` | 서버 주소 |
| `REDIS_PORT` | `6380` | 포트. 배포 주소의 실제 포트 지정 |
| `REDIS_USERNAME` | 빈 값 | ACL 사용자명 |
| `REDIS_PASSWORD` | 빈 값 | 인증 비밀번호 |
| `REDIS_SSL` | `false` | TLS 사용 여부 |

연결·명령 제한시간은 각각 3초입니다. Redis Repository 자동 탐색은 사용하지 않습니다. 로컬에서는 `REDIS_CLUSTER_MODE=false`, `ELASTICACHE_CACHE_NAME=`으로 설정합니다. 캐시 이름이 있으면 Serverless IAM 인증을 사용하고 토큰을 생성·갱신합니다. 인프라 변수와 동작은 [IAM 연결](iam-connection.md)에 기록합니다. 실제 배포 IAM 연결 검증은 별도입니다.

## Spring 연결 검증

Valkey 실행 후 명시적으로 활성화한 연결 테스트를 수행합니다. 기본 테스트에서는 건너뜁니다. 테스트는 고유한 `quote:development:connection-check:*` 키에 저장·조회·TTL·삭제를 검증하고 정리합니다. 키움이나 DB에는 연결하지 않습니다.

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*RedisConnectionLiveTests' --rerun-tasks
Remove-Item Env:REDIS_LIVE_TEST
```

## 현재가·호가 저장 코드

`RedisQuoteStore`는 기존 `StockPrice`, `OrderBook` 객체와 수집 담당 ID·연결 ID를 묶어 JSON 문자열로 저장하고 복원합니다. 이번 개발 단계에서 JSON 형식이 변경되었으며 이전 형식의 값은 주문용 공유 조회에서 재사용하지 않습니다.

- 현재가 키: `quote:{종목코드}:price`
- 호가 키: `quote:{종목코드}:orderbook`
- TTL: 저장할 때마다 1일로 갱신
- 값이 없으면 빈 결과 반환. 연결 실패나 잘못된 JSON은 오류로 전달
- TTL은 보관 기간이며 주문 사용 가능 여부를 뜻하지 않음
- 구독 상태 키: `quote:005930:subscription`. 담당 ID·연결 ID·마지막 메시지 시각을 저장하고 TTL 90초 적용

위 현재가·호가 키의 `{종목코드}`는 설명용 자리 표시자입니다. 실제 예시는 `quote:005930:price`, `quote:005930:orderbook`입니다.

Spring 운영 스트림은 Redis 저장소를 사용합니다. 유효한 WebSocket 구독 상태에서만 Redis 시세를 읽으며, 연결 종료·구독 해제 시 해당 캐시를 삭제합니다. 새 등록 전에 이전 캐시를 지우므로 재시작·재연결 이전 시세를 재사용하지 않습니다. Redis 쓰기 실패 시 수신 연결을 종료하고 체결 이벤트를 전달하지 않습니다.

담당의 WebSocket을 켜려면 `KIWOOM_COLLECTOR_ENABLED=true`와 기존 `KIWOOM_STREAM_ENABLED=true`를 모두 설정합니다. 일반 BE는 `KIWOOM_STREAM_ENABLED=true`, `KIWOOM_COLLECTOR_ENABLED=false`로 설정해야 공유 시세 경로가 켜집니다. 기본값은 꺼짐입니다. 비담당 스트림은 키움 연결이나 공유 캐시 삭제를 수행하지 않습니다.

수집 담당은 시작 시 `coord:kiwoom:collector`에 고유한 서버 실행 ID를 저장합니다. 5초마다 자신의 값인 경우에만 TTL을 15초로 갱신합니다. 다른 담당이 있으면 두 번째 담당의 시작은 실패합니다. 만료·소유권 변경·Redis 오류가 발생하면 해당 실행은 재획득하지 않고 웹소켓과 시세 사용을 중단합니다. 복구하려면 담당 BE를 재시작해야 하며 자동 인계는 없습니다. 비담당 BE는 이 키를 생성하지 않습니다.

종료할 때도 자신의 값일 때만 삭제합니다. 소유권을 잃은 스트림은 공유 시세를 삭제하지 않으므로 남은 값은 TTL로 정리됩니다. 활동 표시는 웹소켓 인증·종목 등록 성공을 의미하지 않습니다. 이 상태들은 별도로 검증합니다.

인프라에는 `coord:*` 키 접근과 `GET`, `SET`(NX/EX/PX), `EXPIRE`, `PEXPIRE`, `DEL`, `EVAL`, `EVALSHA`, `SCRIPT LOAD` 허용을 전달해야 합니다. 시세 저장의 `quote:*` 키와 Pub/Sub의 `quotes:*` 채널에도 접근 권한이 필요하며 채널 명령은 `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`입니다. 소유자 조건부 갱신·삭제 스크립트는 단일 키만 사용합니다. 로컬은 배포 ACL 제한을 검증하지 않습니다. 명령 지원 목록과 ACL 허용은 별개이므로 배포 환경에서 실제 실행을 확인해야 합니다. [AWS 공식 명령 지원 문서](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SupportedCommands.html)를 참고합니다.

## 일반 BE의 구독 요청과 알림

1. 주문을 DB에 저장한 뒤 PENDING 주문의 종목으로 Redis 채널 구독을 갱신합니다. 접수 중 임시 수요 키와 구독 완료 대기는 사용하지 않습니다.
2. 담당 BE가 1초마다 DB PENDING 종목을 읽어 AI의 `price:{code}`, `orderbook:{code}` 채널을 구독합니다.
3. 각 BE는 DB 대기 주문 종목의 `quotes:005930` 같은 채널을 개별 지정합니다. `PSUBSCRIBE`는 사용하지 않습니다. 현재가·호가를 저장한 뒤 종목코드·종류·시각·담당/연결 ID·실제 데이터가 포함된 알림을 발행합니다.
4. 현재가 알림은 각 BE의 기존 주문 체결 서비스로 전달합니다. 알림 수신 시와 주문 판단 직전에 담당·연결 ID를 확인합니다. DB의 계좌 잠금과 주문 상태 검사가 체결·현금 차감의 중복을 방지합니다. 호가는 공유 캐시에서 읽으며 기존 시장가 검증의 수신/시장 시각 5초 기준을 유지합니다.
5. Pub/Sub 재구독 ACK는 새 시세를 만들지 않습니다. AI 채널의 새 세션 이후 실제 수신한 시세만 체결에 사용합니다. 누락된 중간 이벤트는 재생하지 않습니다. 처리 스레드는 1개, 대기 큐는 256개로 제한합니다. 과부하·장애 로그와 실제 부하 검증은 배포 전 확인해야 합니다.

공유 시세 조회는 담당 활동 키, 종목 등록 상태와 시세의 연결 ID가 모두 일치해야 값을 반환합니다. 일반 BE는 키움 웹소켓에 연결하지 않고 Redis에서 읽습니다. 최초 현재가가 아직 없으면 DB 대기 주문을 유지한 채 다음 현재가 알림을 기다립니다. 구독 실패는 이미 커밋된 주문을 취소하지 않으며 주기적 갱신으로 복구합니다.

REST 담당 전달과 TR별 제한은 구현했습니다. 종목정보·토큰 캐시 이전도 구현했습니다. 배포 환경 검증 전이므로 현재 상태를 다중 BE 운영 검증 완료로 취급하지 않습니다. 담당 소유권 확인과 시세 쓰기는 별도 명령입니다. 이전 담당의 지연 쓰기가 발생하더라도 공유 조회는 담당·연결 ID가 다른 값을 주문에 사용하지 않으며, 원자적 쓰기 차단 자체는 아직 보장하지 않습니다. 현재가의 추가 신선도 정책과 배포 ACL·TLS·장애/부하 검증은 후속 확인 대상입니다.

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test
Remove-Item Env:REDIS_LIVE_TEST
```

실제 Valkey 테스트는 `localhost:6380` 전용입니다. 실행 중인 수집 담당이 있으면 활동 키를 덮어쓰지 않고 테스트를 실패시킵니다. 테스트 전용 종목을 사용하며, 두 구독자가 같은 알림을 받아도 H2 DB에서 체결 기록과 현금 차감이 한 번만 발생하는지 확인합니다. 키움에는 실제로 연결하지 않습니다.

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*RedisQuoteStoreLiveTests' --rerun-tasks
Remove-Item Env:REDIS_LIVE_TEST
```

테스트는 로컬 `localhost:6380`에서 테스트 전용 종목 `TST001`을 사용하고 키를 정리합니다. 가격 정밀도, 시각, 호가 목록 복원, 읽기 공유, TTL 갱신과 잘못된 JSON 거부를 검증합니다.

```powershell
docker compose -f compose.valkey.yaml stop
docker compose -f compose.valkey.yaml down
```

이 환경은 로컬 연결 확인용입니다. 영속 볼륨이 없어 컨테이너 재생성 시 데이터가 사라집니다. localhost에만 공개하며, 인증·TLS·배포 ACL은 아직 설정하지 않았습니다.

이미지는 잠정적으로 `valkey/valkey:8-alpine`을 사용합니다. 인프라 버전이 확정되면 `STOCK_SPOON_VALKEY_IMAGE`로 지정하고 같은 ACL·명령 제약을 검증합니다. PC 포트는 `STOCK_SPOON_VALKEY_PORT`로 변경할 수 있습니다.

## 내부 REST 조회 설정

일반 BE의 현재가(ka10001), 종목정보(ka10100), 일봉(ka10081) 조회는 담당 BE의 `POST /internal/kiwoom/query`로 전달합니다. 임의 외부 주소·본문·주문 TR은 허용하지 않습니다. 일반 BE는 키움 토큰이나 앱 키를 내부 요청에 넣지 않습니다.

| 환경변수 | 담당 BE | 일반 BE |
|---|---|---|
| `KIWOOM_COLLECTOR_ENABLED` | `true` | `false` |
| `KIWOOM_STREAM_ENABLED` | `true` | `true` |
| `KIWOOM_COLLECTOR_URL` | 생략 가능 | 담당 BE의 주소(예: `http://localhost:8080`) |
| `KIWOOM_COLLECTOR_SECRET` | 공통 전용 비밀키 | 담당과 동일한 비밀키 |
| `KIWOOM_APP_KEY`, `KIWOOM_APP_SECRET` | 필요 | 생략 가능 |

`X-Kiwoom-Collector-Key` 헤더 하나로 인증합니다. 비밀키가 비어 있는 담당 서버는 시작에 실패하며, 일반 BE는 담당 주소·비밀키가 없으면 조회를 거절합니다. 사용자 로그인 JWT와 CSRF 인증은 이 내부 API를 대신 인증하지 않습니다. 비밀키는 충분히 긴 무작위 값으로 생성해 비밀 관리 도구나 환경변수로 주입하고 저장소·로그에 남기지 않습니다.

배포 주소는 인프라에서 접근을 제한한 내부 주소로 지정하고, 전송 구간 보호가 필요한 환경에서는 HTTPS를 사용합니다. 주소는 HTTP(S)의 서버 주소만 허용하며 자격증명·쿼리·경로를 포함하지 않습니다. 리다이렉트를 따르지 않아 비밀키를 다른 서버로 전달하지 않습니다. 로컬에서 BE 두 개를 실행할 때에는 일반 BE의 `SERVER_PORT`를 다른 포트로 지정합니다.

요청 전후 Redis의 담당 ID를 확인하고, 담당은 요청에 담긴 ID와 자신의 실행 ID가 일치할 때만 조회합니다. 담당 변경·Redis 오류·내부 HTTP 실패 시 결과를 사용하지 않으며 일반 BE가 키움을 직접 호출하는 우회는 없습니다. 담당은 토큰 발급 전·TR 대기 후·외부 응답 후 담당 자격을 확인합니다.

현재 담당의 단일 계좌/토큰에 대해 api-id별 최근 1초 호출 시작 시각을 최대 5개 기록합니다. 여유가 있으면 바로 보내고 초과 요청만 가장 오래된 기록이 1초를 지날 때까지 기다립니다. 같은 TR도 응답을 기다리는 동안 다른 허용 요청이 진행하며 다른 TR은 별도로 계산합니다. 실패·토큰 재시도도 실제 요청마다 한 건입니다. 토큰 갱신은 같은 계좌의 카운터를 초기화하지 않습니다. 담당 한 대에서 관리하므로 Redis 호출 횟수 키는 추가하지 않습니다. 사용자가 제공한 공식 FAQ의 실전 TR당·토큰별 5회 기준을 적용하며 모의투자 초당 1회는 별도 환경 정책입니다.

사용자 승인에 따라 담당의 호출 순서 대기는 외부 호출 시도마다 최대 3초입니다. 한도를 기다린 시간이 3초에 도달하면 키움에 보내지 않고 실패합니다. 거절된 요청은 호출 횟수를 소비하지 않으며 내부 조회 API는 기존 방식대로 503을 반환합니다. 잠금 획득 대기 시간도 대기 시간에 포함하며, 실행 스레드가 늦게 배정되면 실제 실패 반환 시각은 3초를 넘길 수 있습니다.

연결 3초·응답 5초 제한은 기존 REST 설정을 유지합니다. 대기 상한은 토큰 확보와 외부 HTTP 응답을 포함한 전체 시간 제한이 아닙니다. 요청이 몰리거나 토큰 갱신이 길어지면 일반 BE가 먼저 시간 초과될 수 있고, 이미 시작된 담당 조회는 완료될 수 있습니다. 내부 전달은 자동 재시도하지 않습니다. 담당에서 기존 8005 토큰 오류만 한 번 갱신 후 재조회하며, 이 재시도의 호출 순서 대기도 별도로 최대 3초입니다.

실제 로컬 HTTP 전달·인증 실패·허용 TR·담당 변경·키움 직접 호출 방지를 테스트합니다. 실제 키움 외부 연결과 배포 네트워크·ACL·TLS는 이 테스트에 포함하지 않습니다.

## 종목정보 공유 캐시

`quote:005930:metadata`에 종목코드·종목명·시장 구분·업종·조회 시각을 JSON으로 저장합니다. TTL은 저장 시점부터 7일이며 읽을 때 늘리지 않습니다. `state`·`orderWarning`은 저장하지 않습니다. 주문 검증의 `stockInfo()`는 이 캐시를 사용하지 않고 담당 BE에서 ka10100을 새로 조회합니다.

보유 조회의 `stockDetails()`는 먼저 Redis를 읽습니다. 값이 없으면 일반 BE가 같은 비밀키 인증을 적용한 `POST /internal/kiwoom/metadata`에 요청합니다. 담당도 캐시를 다시 확인하고, 같은 종목에 대한 동시 요청을 하나의 조회 작업으로 합칩니다. 결과는 Redis에 저장하며 진행 중 작업만 로컬 메모리에 유지했다가 성공·실패 모두 제거합니다. 조회 완료 후 도착하는 요청은 Redis 값을 재사용합니다.

현재가 REST·주문 검증의 신선한 상태 조회는 7일 캐시로 합치지 않습니다. 이 캐시는 WebSocket 현재가·호가 키를 덮어쓰지 않습니다. 잘못된 JSON이나 Redis 읽기·쓰기 장애를 성공으로 처리하지 않고 조회를 실패시킵니다.

로컬 Valkey 테스트는 `quote:TST006:metadata`를 사용해 두 캐시 인스턴스의 공유, TTL 7일과 민감한 주문 상태 제외를 확인하고 키를 정리합니다.

## 키움 토큰 공유 저장

운영 토큰은 `token:kiwoom:access`에 JSON으로 저장합니다. 토큰·실제 만료 시각·앱 키의 SHA-256 식별값을 포함하며 앱 키·앱 시크릿 원문은 저장하지 않습니다. 식별값이 다른 앱이면 기존 토큰을 재사용하지 않습니다. 현재 배포 설계는 이 Redis에 한 개 키움 앱과 한 개 고정 수집 담당을 두는 구조입니다.

TTL은 `실제 만료 시각 - 현재 시각 - 60초`입니다. 조회할 때도 실제 만료 시각의 60초 전 경계를 검사합니다. 담당 BE를 재시작해도 유효한 토큰을 Redis에서 재사용하며, 일반 BE는 토큰을 읽거나 발급·폐기하지 않습니다. 동시 REST·WebSocket 요청의 발급은 담당 BE 안에서 직렬 처리합니다.

8005·WebSocket 인증 실패 시 거부된 토큰과 앱 식별값이 Redis 값에 일치할 때만 Lua로 삭제합니다. 이전 요청의 오류가 이미 갱신된 토큰을 지우지 않습니다. Redis 장애·잘못된 JSON·담당 자격 상실 시 중단하고 메모리 토큰이나 직접 발급으로 우회하지 않습니다. 담당 확인과 캐시 저장은 별도 명령이므로 임대 확인 직후 상실과 쓰기 사이의 경합을 원자적으로 차단하는 것은 아직 보장하지 않습니다.

기존 단독 외부 API 테스트 생성자는 격리된 메모리 캐시를 유지하지만 운영 Spring 빈은 Redis 경로만 사용합니다. 토큰 원문은 응답·로그에 노출하지 않습니다. 로컬 검증은 `token:kiwoom:development-test:{UUID}` 전용 키와 가짜 토큰을 사용합니다.

## 시장 지수 제거

BE의 지수 API·갱신 작업·Redis 지수 저장소는 제거했습니다. 현재 시세 연결은 [AI Valkey 호환](ai-quotes.md)을 따릅니다.

## 실제 키움과 Redis 연결 검증

`KiwoomRedisLiveTests`는 실제 담당 활동 키를 확보하고 Redis 기반 운영 토큰 제공자로 읽기 전용 REST와 WebSocket을 호출한다. `.env.local`의 키움 인증 설정과 `localhost:6380` Valkey가 필요하다. 실행 중인 담당 또는 삼성전자 관련 기존 캐시가 있으면 값을 덮어쓰지 않고 실패한다. 장중 시세가 실제로 발생하는 시간에 실행해야 하며, 장외 LOGIN/REG 성공만으로 시세 수신 성공을 판정하지 않는다.

```powershell
$env:KIWOOM_REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*KiwoomRedisLiveTests'
Remove-Item Env:KIWOOM_REDIS_LIVE_TEST
```

실제 현재가·종목정보 REST, 종목정보 7일 캐시, 실제 LOGIN/REG, 서로 다른 수신 시각의 현재가 알림 2회 이상, 실제 호가 저장과 일반 BE 시세 처리 경로의 Pub/Sub 수신을 검증한다. 담당 활동은 별도 스레드에서 갱신한다. 일반 BE 역할의 토큰 접근을 거절하는지, 새 제공자 및 담당 ID를 바꾼 새 인스턴스가 유효한 Redis 토큰을 재사용하는지도 확인한다. 토큰 값이나 인증 설정은 출력하지 않는다.

테스트는 외부 주문 API 및 기존 개발 DB를 사용하지 않는다. 생성한 종목정보·시세 캐시와 담당 활동 키는 정리하지만 유효한 `token:kiwoom:access`는 다음 담당의 재사용을 위해 남긴다. 담당·일반 시세 처리 객체는 이 테스트 JVM 안에서 구성하며, 별도 JVM 세 개의 구성 검증과 구분한다. 실제 키움 시세를 받는 BE 세 개의 결합 부하 및 배포 환경 검증을 대신하지 않는다.

2026-10-12 장중 실행에서 위 검증 1개가 통과했다. 담당 ID 교체 검증은 컴포넌트 인스턴스 교체이며 실제 서버 프로세스 재시작 시험과 구분한다.

## 장애·복구 검증

`MultiProcessRedisLiveTests.connectionFaultKeepsPendingOrderAndRequiresNewCollectorAndSession`은 별도 JVM 세 개와 공유 H2 TCP DB를 사용한다. 테스트용 TCP 프록시가 BE들의 Redis 연결만 끊었다가 복구한다. 실제 Valkey 컨테이너·데이터·키움 토큰은 중단하거나 삭제하지 않는다. 테스트 BE는 운영과 동일한 Redis 명령·연결 제한시간 3초를 명시한다.

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*MultiProcessRedisLiveTests.connectionFaultKeepsPendingOrderAndRequiresNewCollectorAndSession'
Remove-Item Env:REDIS_LIVE_TEST
```

검증 흐름은 다음과 같다.

1. 잘못된 내부 비밀키로 조회하면 외부 조회 함수에 도달하지 않는다.
2. 지정가보다 높은 시세를 먼저 수신하여 공유 DB 주문을 PENDING으로 유지한다.
3. 테스트 BE들의 Redis 연결을 끊는다. REST·시세 조회는 실패하고 새 주문 접수 서비스는 503으로 거절한다. 주문 저장 콜백은 실행되지 않는다.
4. 체결 조건을 충족하는 이전 출처의 알림을 처리해도 대기 주문·현금·체결 기록은 유지된다. DB 주문 상태 조회와 테스트용 별도 대기 주문 취소는 성공한다.
5. Redis 연결을 복구해도 자격을 잃은 담당은 자동으로 재획득하지 않는다. 활동 키는 15초 TTL로 정리된다.
6. 이전 담당 프로세스를 강제 종료하고 같은 내부 HTTP 포트에서 새 담당을 실행한다. 기존 일반 BE의 조회는 복구되지만 이전 담당 시세는 사용하지 않는다.
7. 새 담당의 연결 ID도 변경한다. 이전 연결 알림은 거절하고 새 연결의 새 시세를 받은 뒤 대기 주문이 한 번만 체결된다. 현금은 1,000,000원에서 880,500원으로 한 번 차감된다.

이 테스트의 외부 키움은 가짜 응답이다. 실제 웹소켓의 정상 종료·재연결은 `KiwoomRedisLiveTests`로 별도 검증한다. 새 연결에서 실제 현재가·호가를 수신하고 같은 담당의 이전 연결 출처를 거절하는지 확인한다. 임의 외부 네트워크 단절·전체 Valkey 서버 중단·실제 MySQL·배포 네트워크·ACL/TLS·부하 시험은 구분하여 남겨 둔다.

## 실제 JVM 세 개 검증

`MultiProcessRedisLiveTests`는 담당 BE 1대와 일반 BE 2대를 **별도 JVM**으로 실행한다. 세 프로세스는 로컬 Valkey와 테스트마다 새로 만드는 H2 TCP DB를 공유한다. 운영 Spring 구성·내부 HTTP 인증·Redis 저장소·Pub/Sub·주문 체결 서비스는 그대로 사용하고, 외부 키움 REST와 WebSocket만 테스트 응답으로 대체한다. 운영 서버에는 테스트 API를 추가하지 않는다.

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*MultiProcessRedisLiveTests'
Remove-Item Env:REDIS_LIVE_TEST
```

종목정보 캐시 누락 시 담당 조회 1회 후 두 일반 BE의 공유, 읽기 전용 REST의 실제 내부 HTTP 전달, 양쪽 시세 알림 수신, 공유 DB 주문의 체결 1건·현금 1회 차감, 담당 중단 뒤 조회 거절·활동 키 정리 후 이전 시세 사용 중단을 확인한다. 종료 정리를 못한 프로세스는 활동 TTL 15초로 중단을 판별하므로 즉시 감지를 보장하지 않는다. 실제 키움 연결·MySQL 잠금·배포 Valkey ACL/TLS·부하 검증은 별도로 진행해야 한다.

테스트는 Windows의 Java 21 실행 파일을 사용하고 테스트 클래스 경로를 자식 JVM에 전달한다. 로컬 Valkey에 기존 담당이 있거나 `TST009` 테스트 키가 있으면 덮어쓰지 않고 실패한다. 테스트용 DB·BE 프로세스·자신이 만든 시세 키는 종료 시 정리한다. 이 테스트와 활동 키를 사용하는 다른 실시간 테스트는 같은 Valkey에서 동시에 실행하지 않는다.

시세 기능을 켠 실제 시작 구성에서 Spring 자동 구성의 알림 컨테이너와 시세 전용 컨테이너가 함께 존재하는 문제가 확인되어, `RedisMarketStream`은 `quoteListenerContainer`를 명시적으로 주입받는다.

## 담당 활동 및 체결 경합 보완 상세

추가 부하 조건은 `REDIS_LIVE_TEST=true`와 `REDIS_LOAD_TEST=true`를 함께 설정해 실행한다. BE 세 개·한 계좌의 매수 주문 100개·연속 시세 40개로 수신 수, 주문별 체결 1회, 현금·보유 수량과 담당 활동을 검증한다. 매수 조건이 맞지 않을 때의 반복 계좌 잠금으로 처리 지연이 관찰됐으며, [측정 문서](quote-order-load-measurement.md)에 결과와 검증 한계를 기록한다.

후속 개선으로 매수·매도 가격 조건 미충족 주문은 체결 서비스 진입 전에 제외한다. 미충족 주문의 만료 규칙은 기존 5초 구독 갱신 주기가 처리하며, 조건이 맞는 주문은 기존 잠금 이후 상태·가격·시세 출처 검증을 유지한다. 같은 부하의 미충족 단계는 약 34.2초에서 6.7초로 줄었으나 체결 단계는 약 14.9초로 남아 있다. 전체 체결 지연 해소를 의미하지 않는다.

이후 대기 주문의 보고서 반복 조회도 제거했다. `findPendingForPrice`가 주문·계좌·보고서를 함께 조회하며 보고서 없는 주문도 반환한다. 같은 로컬 부하의 최신 결과는 미충족 단계 약 0.9초·체결 단계 약 3.5초다. SQL 반복 제거 회귀 테스트와 중복 체결·자산·담당 활동 검증을 포함해 관련 테스트 26개가 통과했다. 실제 계좌 잠금과 체결 트랜잭션은 유지하며 운영 처리 용량을 보장하는 결과는 아니다.

담당 활동 TTL 갱신은 `collectorLeaseScheduler` 전용 스레드에서 실행합니다. 코스피 REST·구독 DB 조회 등 일반 스케줄 작업은 별도 `taskScheduler`를 사용합니다. 느린 조회가 15초 활동 TTL의 갱신을 막지 않도록 분리했으며, 다른 스케줄러가 막힌 상황에서 실제 예약된 활동 갱신이 계속되는 테스트로 검증합니다.

시장가·지정가의 시세 출처 검증을 체결 트랜잭션에 전달합니다. 계좌 잠금과 DB 조회가 끝난 뒤 자산 변경 직전에 같은 담당·연결 세션이 유효한지 다시 확인합니다. 잠금을 기다리는 사이 연결이 바뀌거나 Redis 확인이 실패하면 체결을 중단합니다. Redis 검증과 DB 커밋을 하나의 원자적 연산으로 묶는 구조는 아니며, 마지막 확인 이후의 아주 짧은 경합까지 원자적으로 차단하지는 않습니다.
