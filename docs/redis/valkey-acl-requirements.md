# 인프라 전달용 Valkey 권한과 연결 설정

현재 BE는 시세 서버가 공급한 Redis 원본을 직접 조회·구독한다. 과거 BE 시세 저장·중계 구현은 제거했다. [AI 시세 연동](ai-quotes.md), [IAM 연결](iam-connection.md)을 함께 참고한다.

## 키와 채널

| 구분 | 범위 | 용도 |
|---|---|---|
| 읽기 키 | `price:*`, `orderbook:*` | 원본 현재가·호가 조회. BE 쓰기·삭제 불필요 |
| 구독 채널 | `price:*`, `orderbook:*` | 각 BE의 개별 종목 구독 |
| 읽기·쓰기 키 | `quote:*` | 남은 종목정보 캐시 `quote:{code}:metadata` |
| 읽기·쓰기 키 | `token:*` | 남은 키움 REST 토큰 |
| 읽기·쓰기 키 | `coord:*` | 남은 키움 REST 담당 상태 |
| 읽기·쓰기 키 | `session:*` | 팀에서 정한 임시 상태 영역. 현재 ACL 테스트에서 TTL 저장만 확인 |

원본 읽기는 `%R~price:* %R~orderbook:*`, 채널은 `&price:* &orderbook:*`로 구분한다. `quotes:*` 중계 채널과 과거 `quote:{code}:price/orderbook/subscription`은 사용하지 않는다.

## 명령 권한

- 기존 REST 캐시·토큰·담당 상태: `GET`, `SET`(NX/EX/PX 포함), `DEL`, `EXPIRE`, `PEXPIRE`, `EVAL`, `EVALSHA`, `SCRIPT LOAD`
- 시세 채널: `SUBSCRIBE`, `UNSUBSCRIBE`
- 연결·검증: `AUTH`, `HELLO`, `PING`, `CLIENT SETINFO/SETNAME/ID`, `TTL`, `PTTL`
- BE의 `PUBLISH`, `PSUBSCRIBE`, `KEYS`, ACL 관리 권한은 필요하지 않다.

로컬 ACL 테스트는 위 범위와 원본 시세 쓰기·삭제·발행 거절을 검증한다. Serverless·Cluster·TLS·IAM의 실제 배포 권한은 별도 확인해야 한다. 배포 ACL을 자동 변경하는 작업은 포함하지 않는다.

## 연결 설정

IAM 배포는 `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_SSL`, `REDIS_CLUSTER_MODE`, `AWS_REGION`, `ELASTICACHE_CACHE_NAME`을 사용한다. 고정 비밀번호 대신 인스턴스 자격으로 토큰을 생성·갱신한다. 네트워크 경로와 캐시·사용자 접속 권한도 필요하다. 실제 인스턴스 연결 검증 완료를 의미하지 않는다.

비밀번호 원문을 문서·Git·채팅에 기록하지 않는다. 역할별 ACL 사용자 분리는 이번 작업에 포함하지 않았다.

## 로컬 재현

```powershell
$env:REDIS_LIVE_TEST = 'true'
.\gradlew.bat test --tests '*RedisAclLiveTests' --tests '*RedisConnectionLiveTests'
Remove-Item Env:REDIS_LIVE_TEST
```

로컬 관리자 연결이 임시 ACL 사용자와 테스트 전용 원본 시세를 만든다. 제한된 BE 연결은 원본을 조회·구독한다. 기존 담당 또는 같은 테스트 키가 있으면 실패하며 실제 키움 토큰은 변경하지 않는다. 종료 시 자신이 만든 키와 사용자만 정리한다.
