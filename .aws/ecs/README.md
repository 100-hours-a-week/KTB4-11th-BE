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
