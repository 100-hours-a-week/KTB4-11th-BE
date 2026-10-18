# Backend ECS 배포

운영 ECS의 실행 설정은 [backend.json](backend.json), 배포는 [cd-ecs-v2.yaml](../../.github/workflows/cd-ecs-v2.yaml)에서 관리합니다.

## 배포 흐름

```text
dev push → 빌드·테스트 → ECR 업로드 → 개발 EC2 배포 → ECS 배포
```

개발 EC2 배포가 성공하면 동일한 커밋 SHA의 이미지를 ECS에도 배포합니다. ECS CD는 이미지 SHA의 커밋을 checkout하고, 같은 커밋의 템플릿에 이미지 주소를 넣어 새 Task Definition 리비전을 등록합니다. 이후 서비스를 갱신하고 배포 ID를 기준으로 완료 여부를 확인합니다.

## 실행 설정 변경

다음 설정은 `backend.json`을 수정한 뒤 CD로 적용합니다.

- CPU·메모리, 환경변수, 컨테이너 헬스체크
- 포트, 로그 설정, Task Role·Execution Role ARN
- SSM Parameter Store·Secrets Manager의 Secret 참조

비밀값은 직접 적지 않고 `secrets[].valueFrom`으로 참조합니다. 초기 템플릿은 AWS의 실행 설정과 Secret 참조를 옮긴 것이며, Secret 실제 값은 포함하지 않습니다.

## DB 접속 정보

DB 접속 정보는 모두 SSM Parameter Store에서 주입합니다. 템플릿에 주소를 직접 적지 않습니다.

| 환경변수 (`application.yaml`) | SSM 파라미터 | 관리 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `/stockspoon/v2/prod/backend/SPRING_DATASOURCE_URL` (`String`) | Cloud Terraform이 생성. ProxySQL `6033` 주소 |
| `MYSQL_USER`, `MYSQL_PASSWORD` | `/stockspoon/v2/prod/backend/MYSQL_USER`, `MYSQL_PASSWORD` (`SecureString`) | CLI로 등록 |

## DB 전환 중 쓰기 요청 보관

`DB_WRITE_REPLAY_ENABLED`가 `true`이고 업무 변경 API 또는 인증 관련 DB 쓰기가 MySQL의 명시적 read-only 오류나 확인된 ProxySQL writer 오류로 거절되면, 백엔드는 재생용 업무 명령을 `stockspoon-v2-prod-db-write-replay.fifo`에 보관하고 HTTP `202 Accepted`와 `request_id`를 반환합니다. 큐에는 SQL문이 아니라 백엔드 서비스가 나중에 처리할 버전이 붙은 명령이 저장됩니다. 로그인은 프로필 동기화 명령을, 토큰 재발급·로그아웃은 refresh token 해시를 담은 폐기 명령을 보관합니다. 대기 응답에는 인증 쿠키가 포함되지 않으므로 DB 전환 뒤 사용자가 다시 로그인해야 합니다. SQS 소비자/재생 작업은 별도로 구현해야 하며, 메시지가 큐에 들어간 것만으로 DB 반영이 완료되지는 않습니다.

운영 템플릿의 기본값은 `false`이며 `DB_WRITE_REPLAY_PROXYSQL_ERROR_CODES`도 빈 값입니다. 재생 기능은 명시적 MySQL read-only 오류와 관측 로그에서 확인해 설정한 ProxySQL vendor code만 큐잉합니다. 실제 오류를 보기 전에는 ProxySQL code를 추정해 넣지 않습니다. SQS 전송 권한이 Backend Task Role에 연결되어 있어야 합니다. `db_write_failure_observed`와 `db_write_sql_exception` 로그는 재생 기능이 꺼져 있어도 예외가 포착된 요청에서 SQL 예외 타입, vendor error code, SQLState, 메시지와 HTTP 요청 ID를 기록합니다. 같은 ID의 `http_request` 로그에서 응답 상태와 `elapsedMs`를 확인해 실제 JDBC 응답과 API 지연을 연결할 수 있습니다. 체결 이벤트와 대회 스케줄러는 DB에 남아 있는 pending/candidate 상태를 다음 시세 이벤트 또는 주기에 다시 조회해 처리합니다.

이 변경은 주문·체결·정산의 가격, 수량, 순서, 상태 계산 규칙을 바꾸지 않습니다. 정상 요청 응답도 기존 상태를 유지하며, DB 오류가 관측된 요청은 재생 기능이 활성화되고 오류가 분류된 경우에만 `202 Accepted`로 바뀝니다. 인증 대기 요청은 사용자가 DB 전환 뒤 다시 로그인해야 합니다.

SSM 값은 Task가 시작될 때만 읽습니다. **값을 바꿔도 실행 중인 Task에는 반영되지 않으므로 수동 재배포가 필요합니다.** 아래 수동 배포에 dev 최신 커밋 SHA를 넣어 실행하거나, Cloud에서 `aws ecs update-service --force-new-deployment`로 Task를 교체합니다.

## 수동 배포

GitHub Actions의 `Backend Dev CD (V2 ECS)`에서 다음과 같이 실행합니다.

1. 실행 브랜치로 `dev`를 선택합니다.
2. `image_tag`에 ECR에 업로드된 이미지의 40자리 커밋 SHA를 입력합니다.
3. 해당 SHA의 이미지와 같은 커밋의 템플릿으로 배포합니다.

SHA가 일치하지 않거나 해당 커밋에 템플릿이 없으면 AWS 인증 전에 실패합니다. 템플릿 도입 전 커밋은 이 워크플로로 배포할 수 없습니다. AWS 최신 리비전을 복사하거나 ECR 최신 이미지를 검색하지 않습니다.

## Cloud와의 역할 분담

| 관리 주체 | 담당 설정 |
|---|---|
| 앱/CD | Task Definition 실행 설정, 이미지 SHA, 리비전 등록·배포·검증 |
| Cloud | ECS 클러스터·서비스, 타깃 그룹, IAM 정책, 보안 그룹·네트워크 |

포트, Role ARN, DB·캐시 주소, Secret 경로처럼 인프라와 연결되는 값은 Cloud와 협의해 변경합니다.

```text
Cloud 리소스 준비 → 앱 템플릿 변경·배포 → 이전 리소스 정리
```

Cloud의 Task Definition은 최초 서비스 생성용으로만 유지됩니다. 이후 실행 설정은 앱 템플릿이 기준이며, 최초 생성용 Terraform 설정을 수정해도 기존 앱 설정에 반영되지 않습니다.
