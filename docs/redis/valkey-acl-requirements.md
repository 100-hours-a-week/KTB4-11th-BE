> AI 연동에는 기존 권한에 `%R~price:* %R~orderbook:* &price:* &orderbook:*`를 추가해야 합니다. 앱은 AI 키에 저장·삭제·발행하지 않습니다. 실제 배포 ACL은 별도 검증 대상입니다.

# 인프라 전달용 Valkey 권한과 연결 설정

현재 데이터 명령·ACL 검증은 로컬 Valkey 8에서 수행했다. 이후 인프라의 Serverless 엔드포인트·TLS·IAM 설정을 전달받아 연결 코드를 보완했다. 실제 인스턴스의 배포 연결 검증 완료를 의미하지 않는다. [IAM 연결](iam-connection.md)을 참고한다.

2026-10-12 로컬 ACL 검증과 기본 Spring 연결 검증 2개가 모두 통과했다. 임시 ACL 사용자의 삭제 응답도 확인했다. 운영 코드 변경은 없었다.

## 허용할 키와 채널

| 구분 | 범위 | 용도 |
|---|---|---|
| 키 | `quote:*` | BE 검증 시세·종목정보 및 구독 연결 상태 |
| 키 | `session:*` | 팀에서 지정한 임시 사용자 상태 영역. 이번 테스트는 TTL 있는 임시 값 저장만 확인 |
| 키 | `token:*` | 키움 토큰과 만료 정보 |
| 키 | **`coord:*`** | 담당 활동 키·접수 중 구독 수요·만료 순서 색인. 추가 허용 필요 |
| 키 (GET) | `price:*`, `orderbook:*` | AI 원본 스냅샷 읽기 |
| Pub/Sub 채널 | `price:*`, `orderbook:*` | 담당 BE가 AI 알림 구독 |
| Pub/Sub 채널 | `quotes:*` | 검증 후 BE 내부 알림 |

키 권한과 채널 권한은 별개다. 로컬 ACL은 키에 `~quote:* ~session:* ~token:* ~coord:*`, 채널에 `&quotes:*`를 사용해 검증했다. `quotes:*` 이름의 **키**를 저장하거나 `quote:*` 이름의 **채널**에 발행·구독하는 접근은 거절됐다.

## 명령 권한

| 구분 | 로컬 검증에서 허용한 명령 |
|---|---|
| 저장·조회·정리 | `GET`, `SET`(NX/EX/PX 포함), `DEL`, `EXPIRE`, `PEXPIRE` |
| 구독 수요 색인 | `TIME`, `ZADD`, `ZRANGE`, `ZREM`, `ZREMRANGEBYSCORE` |
| 조건부 처리 | `EVAL`, `EVALSHA`, `SCRIPT LOAD` |
| 종목별 Pub/Sub | `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE` |
| TTL 검증·관찰 | `TTL`, `PTTL` |
| 연결·클라이언트 설정 | `AUTH`, `HELLO`, `PING`, `CLIENT SETINFO`, `CLIENT SETNAME`, `CLIENT ID` |

로컬은 위 명령만 허용한 사용자로 Lettuce 연결과 운영 저장소 코드를 검증했다. 이 표는 실제 허용해 시험한 목록이며, 모든 연결 명령이 매번 실행되거나 반드시 필요하다는 뜻은 아니다. 배포 서비스의 명령 지원과 ACL 허용은 실제 연결에서 확인해야 한다. `TTL`·`PTTL`은 검증·관찰 명령이다.

배포의 클러스터 연결에는 **`CLUSTER NODES`와 `INFO`도 확인해야 한다.** 현재 Lettuce 7.5.2의 토폴로지 조회·갱신 코드가 두 명령을 사용한다. 기존 단일 노드 로컬 ACL 시험에 포함한 명령은 아니며, 배포 사용자의 추가 권한으로 인프라에 전달한다. 전체 `CLUSTER` 관리 권한이나 `+@all`을 요구하는 것은 아니다. AWS는 Serverless에서 가상 클러스터 정보를 반환하는 `CLUSTER NODES`를 지원한다고 안내한다. [AWS 지원 명령](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SupportedCommands.html)

`PSUBSCRIBE`, `KEYS`, 애플리케이션 사용자의 ACL 관리는 허용하지 않았다. 코드의 Lua 스크립트와 개별 채널 사용은 이 제한 안에서 통과했다. 실제 접수 수요 키의 `{accepting}` 해시 태그는 유지해야 한다. Cluster 슬롯·Serverless 서비스 제약은 로컬 단일 노드 결과만으로 검증했다고 볼 수 없다.

## 필요한 배포 정보

- 엔드포인트·포트와 BE에서 접근 가능한 네트워크 경로
- TLS 사용 여부와 인증서 신뢰 설정
- 인프라에서 선택한 IAM 사용자 설정과 캐시·사용자에 대한 인스턴스 접속 권한
- 사용자명과 비밀번호의 비밀 관리·주입 방식
- 키·채널 범위 및 명령 ACL의 실제 설정

비밀번호 원문을 문서·Git·채팅에 기록하지 않는다. 사용자/비밀번호 방식은 캐시 이름을 비운 상태에서 `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL`로 주입한다. 현재 배포 IAM 방식은 `REDIS_CLUSTER_MODE=true`, `AWS_REGION`, `ELASTICACHE_CACHE_NAME`을 추가하며 고정 비밀번호 대신 인스턴스 자격으로 토큰을 생성·갱신한다.

현재 로컬 검증은 공통 BE 권한 범위다. 일반 BE의 키움 토큰 사용은 애플리케이션의 담당 역할 검사로 차단한다. 역할별 ACL 사용자 분리는 이번 단계에서 적용하지 않았다.

## 로컬 재현

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*RedisAclLiveTests' --tests '*RedisConnectionLiveTests'
Remove-Item Env:REDIS_LIVE_TEST
```

테스트는 임시 사용자와 비밀번호를 메모리에서 생성하고 기존 기본 사용자를 바꾸지 않는다. `TST010` 시세·가짜 토큰의 UUID 키 등을 사용하며 기존 담당이나 같은 테스트 키가 있으면 실패한다. 실제 `token:kiwoom:access`를 변경하지 않는다. 종료 시 자신이 만든 키와 ACL 사용자를 정리한다. 접수 수요 색인의 자신의 항목만 제거하며 다른 요청을 삭제하지 않는다.
