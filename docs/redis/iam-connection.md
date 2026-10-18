# 인프라 IAM·Serverless 연결

인프라가 제공한 `REDIS_SSL`, `REDIS_CLUSTER_MODE`, `AWS_REGION`, `ELASTICACHE_CACHE_NAME` 이름을 그대로 사용한다. 인스턴스에 IAM 역할이 부여됐다는 사용자 확인에 따라 연결·인증 코드를 보완했다. 실제 인프라의 네트워크·IAM·사용자 ACL 연결 검증은 별도다.

## 연결 선택

| 설정 | 동작 |
|---|---|
| `ELASTICACHE_CACHE_NAME` 없음/빈 값 | 기존 로컬 또는 사용자명·비밀번호 인증. AWS 자격 제공자를 생성하지 않음 |
| `ELASTICACHE_CACHE_NAME` 있음 | ElastiCache Serverless IAM 인증. `REDIS_PASSWORD` 대신 생성한 토큰 사용 |
| `REDIS_CLUSTER_MODE=false` 또는 미설정 | 단일 노드 연결 |
| `REDIS_CLUSTER_MODE=true` | `REDIS_HOST:REDIS_PORT`를 초기 노드로 하는 클러스터 연결 |
| `REDIS_SSL=true` | TLS 사용, 기본 인증서·호스트 검증 유지 |

IAM에서는 사용자명·리전·TLS·클러스터 설정이 필수다. 누락되면 시작 시 오류를 반환하며 비밀번호 방식으로 자동 전환하지 않는다. 현재 IAM 구현은 제공된 **Serverless 캐시**용이다. 일반 node-based 캐시의 IAM 설정으로 그대로 해석하지 않는다.

## 배포 환경변수

```properties
REDIS_HOST=stockspoon-v2-dev-valkey-kmikux.serverless.apn2.cache.amazonaws.com
REDIS_PORT=6379
REDIS_USERNAME=stockspoon-v2-dev-valkey-backend
REDIS_SSL=true
REDIS_CLUSTER_MODE=true
AWS_REGION=ap-northeast-2
ELASTICACHE_CACHE_NAME=stockspoon-v2-dev-valkey
```

고정 AWS 액세스 키나 Valkey 비밀번호를 요구하지 않는다. AWS SDK 기본 자격 체인이 인스턴스 역할의 자격을 사용한다. 해당 체인은 환경변수·시스템 설정·프로필 등도 확인하므로 배포 환경의 자격 설정이 의도한 인스턴스 역할을 사용하도록 인프라에서 관리한다. 실제 비밀값은 문서·Git·로그에 기록하지 않는다.

서명 대상은 호스트 엔드포인트가 아닌 **캐시 이름**이며 `Action=connect`, `User`, `ResourceType=ServerlessCache`, `elasticache` 서비스와 리전으로 서명한다. 토큰 유효 기간은 15분이다. 새 연결·재연결 시 토큰을 생성하고, 장시간 연결은 Lettuce streaming credentials와 `ON_NEW_CREDENTIALS`를 사용해 10분마다 새 토큰으로 재인증한다. 인스턴스 자격 조회는 Netty 이벤트 루프 밖에서 실행한다. 주기 갱신 실패는 예외 종류만 기록하고 다음 주기에 재시도한다. 최초 자격 확보 실패는 연결 실패다.

AWS는 IAM 연결을 12시간 후 종료하며 새 토큰으로 재인증하면 연결을 연장할 수 있다고 안내한다. 위 주기 재인증은 일반 명령 연결과 Pub/Sub 연결에 제공한다. 실제 배포 연결이 장시간 유지되는지는 인프라 환경에서 검증한다. [AWS IAM 인증 안내](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/auth-iam.html)

## 로컬 개발

```properties
REDIS_HOST=localhost
REDIS_PORT=6380
REDIS_SSL=false
REDIS_CLUSTER_MODE=false
ELASTICACHE_CACHE_NAME=
```

로컬 Valkey의 인증이 없으면 사용자명·비밀번호도 비운다. 로컬에 AWS 자격을 설정할 필요는 없다. 배포 환경변수를 그대로 로컬에 복사하면 캐시 이름에 따라 IAM이 활성화되므로 위 설정을 별도로 사용한다. 연결·명령 제한시간은 기존 3초를 유지한다.

## 실제 인프라 확인 범위

- 인스턴스 역할에 캐시와 사용자에 대한 `elasticache:Connect` 권한이 있고, Valkey 사용자가 IAM 방식으로 연결된 상태인지 확인한다. IAM 사용자 이름과 사용자 ID는 같아야 하며 코드의 `User`는 `REDIS_USERNAME`을 사용한다.
- BE 인스턴스에서 엔드포인트에 TLS로 접근할 수 있어야 한다. 인스턴스 자격 제공 경로도 정상이어야 한다.
- `coord:*`와 기존 키·채널 및 Lua 명령 권한을 [ACL 요구 사항](valkey-acl-requirements.md)대로 적용한다. IAM 접속 권한과 Redis 데이터 명령 권한은 별개다.
- 현재 Lettuce 클러스터 초기화·갱신에 사용하는 `CLUSTER NODES`, `INFO`의 권한도 확인한다. 기존 단일 노드 ACL 시험과 구분한다.
- 실제 인스턴스에서 초기 연결·재연결·10분 이상 Pub/Sub 유지 및 재인증, TTL·Lua·종목별 알림을 확인한다. 실제 Serverless 클러스터 제약은 로컬 단일 노드 시험으로 대체하지 않는다.

서명·갱신 테스트는 가짜 AWS 자격으로 수행한다. 연결 구성 테스트는 AWS/Valkey에 접속하지 않고 구성을 검증한다. 기존 로컬 Valkey 시험과 실제 배포 IAM 시험을 구분해 기록한다.

## 구현 후 로컬 검증 결과 — 2026-10-12

최종 `test bootJar spotbugsMain` 실행은 성공했다. 로컬 Valkey·ACL·다중 BE·장애 복구·REST 및 주문 부하를 켠 전체 290개 중 284개 통과, 실제 키움 연결 테스트 6개 건너뜀, 실패·오류 0건이다. IAM 서명·세션 자격·주기 갱신·초기 및 주기 갱신 오류 회복·최초 연결 자격 오류·설정 선택/누락/기본값 관련 테스트 8개를 포함한다.

검토에서 발견한 첫 스트리밍 갱신 실패 후 갱신 중단 경로는 테스트로 재현하고 보완했다. 최초 연결용 자격 오류는 그대로 연결 실패로 전달한다. 수정 후 검토에서 남은 중대한 결함은 없었다. 예시 환경변수 이름과 문서 링크도 확인했다.

같은 로컬 주문 부하에서 일반 BE 두 대가 각각 시세 알림 40개를 받았고, 주문 100개가 각각 한 번 체결돼 보유 100주·현금 8,050,000원을 유지했다. 이 실행의 미충족 단계는 1.124초, 체결 단계는 3.968초였다. 실제 AWS IAM 권한·Serverless 네트워크·장시간 Pub/Sub 연결·MySQL 연동을 검증한 결과는 아니다. 실제 인스턴스 검증은 인프라 환경에서 수행한다.
