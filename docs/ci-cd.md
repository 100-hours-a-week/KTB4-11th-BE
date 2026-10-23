# CI/CD 이미지 빌드

CI에서 검사와 빌드가 끝난 `build/libs/application.jar`를 이미지에 사용한다.
개발 CI는 이 파일을 같은 실행의 artifact로 전달하고, main CI는 같은 job의 파일을 사용한다.
PR 검사에서는 이미지를 푸시하거나 배포하지 않는다.

로컬 이미지 빌드는 먼저 JAR를 생성해야 한다.

```sh
./gradlew bootJar
docker build -t stockspoon-backend:local .
```

Docker는 JAR를 다시 컴파일하지 않고 Spring Boot tools로 레이어를 추출한다.
의존성, Boot loader, snapshot 의존성, 애플리케이션을 별도 레이어에 복사한다.
실행 경로와 사용자, Java 21 런타임은 기존과 동일하다.

GitHub Actions는 Buildx의 `gha` 외부 캐시를 사용한다.
V1과 V2는 각각 `backend-v1`, `backend-v2` scope로 구분한다.
첫 실행과 의존성 또는 베이스 이미지가 바뀐 실행에서는 캐시 효과가 작을 수 있다.
캐시 절감량과 artifact 전송 비용은 실제 CI 실행에서 비교한다.

# V2 배포 시간 계측

개발 CD의 SSH 로그에서 `TIMING`을 검색하면 lock 대기, 이미지 pull, 컨테이너 교체,
healthy 대기, Nginx 재생성과 전체 시간을 초 단위로 확인할 수 있다.
실패 시 실패 단계와 롤백의 pull·재생성 시간도 기록한다.
`container_recreate`는 Compose 명령 전체이므로 기존 컨테이너 종료와 의존 서비스 대기가 포함될 수 있다.
`health_wait`는 해당 명령이 반환된 뒤부터 측정한다.

ECS CD는 task definition 등록, 서비스 갱신, rollout 완료 대기를 로그와 실행 요약에 기록한다.
15초 간격으로 신규 태스크의 running/pending 수와 배포 상태를 관측하고,
배포 이후 발생한 서비스 이벤트를 중복 없이 기록한다.
이벤트의 target 등록·해제와 draining 시각을 비교해 배포 지연 구간을 확인한다.
`first_observed_new_tasks_running`은 신규 태스크가 원하는 개수만큼 RUNNING으로 관측된 시간이며,
ALB healthy 도달 시간을 의미하지 않는다. 관측 시점에는 폴링 간격만큼 오차가 있다.

상세 태스크 타임스탬프 수집은 선택 사항이다. 배포 역할에 `ecs:ListTasks`,
`ecs:DescribeTasks` 권한이 있으면 해당 배포의 실행 중 태스크(최대 100개)의
생성·이미지 pull 시작/종료·실행 시각을 기록한다.
권한이 없으면 안내만 출력하고 배포 결과는 유지한다. 종료된 태스크의 전체 이력과
정확한 ALB healthy 도달 시각은 이 계측에 포함되지 않는다.
