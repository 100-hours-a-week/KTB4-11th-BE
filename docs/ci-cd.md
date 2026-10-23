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
