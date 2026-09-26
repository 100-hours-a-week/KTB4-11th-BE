# MySQL 전환 및 로컬 실행 기록

> 기준: `feat/20-MySQL`의 백엔드 코드와 2026-09-25 로컬 검증 결과. 이 문서는 MySQL 전환 작업을 기록하며, 이전 로그인·계좌 문서의 H2 설명은 작성 당시 상태를 나타낸다.

## 1. 변경 범위

실행용 DB를 메모리 H2에서 MySQL로 변경했다. 자동 테스트는 H2를 계속 사용한다. 기존 H2는 메모리 DB였으므로 이전할 영구 데이터는 없었다. 로컬 MySQL은 8.4.11이다.

## 2. 로컬 DB와 계정 준비

`root` 관리자 계정으로 `stockspoon` DB를 `utf8mb4` 문자셋과 `utf8mb4_0900_ai_ci` 정렬 규칙으로 생성했다. `stockspoon_app@localhost` 계정을 만들고 `stockspoon.*`에 대한 권한을 부여했다. 이 권한은 로컬 개발 중 JPA가 테이블을 생성·변경하는 데도 사용된다. 앱은 `root`로 접속하지 않는다.

설정에 사용한 SQL의 형태:

    CREATE DATABASE IF NOT EXISTS stockspoon
      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
    CREATE USER 'stockspoon_app'@'localhost' IDENTIFIED BY '<local-password>';
    GRANT ALL PRIVILEGES ON stockspoon.* TO 'stockspoon_app'@'localhost';

계정 비밀번호는 이후 로컬에서 변경했다. 실제 값은 문서나 Git에 기록하지 않고 `.env.local`에만 보관한다. `.env.local`은 `.gitignore`에 포함된다. 운영 환경에는 별도의 계정 비밀번호를 사용한다.

## 3. Gradle 의존성

`build.gradle`에서 H2를 실행용 의존성에서 테스트 실행용 의존성으로 옮기고 MySQL JDBC 드라이버를 추가했다.

    runtimeOnly 'com.mysql:mysql-connector-j'
    testRuntimeOnly 'com.h2database:h2'

애플리케이션 코드는 MySQL 드라이버 클래스를 직접 참조하지 않는다. Spring Data JPA가 실행 중 JDBC 드라이버를 사용하므로 `runtimeOnly`로 선언했다. 테스트는 `src/test/resources/application.yaml`에서 H2 드라이버와 메모리 DB 주소를 명시한다.

## 4. Spring Boot 접속 설정

`src/main/resources/application.yaml`의 실행용 설정:

    spring:
      datasource:
        url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/stockspoon}
        username: ${MYSQL_USER:stockspoon_app}
        password: ${MYSQL_PASSWORD}
      jpa:
        hibernate:
          ddl-auto: ${JPA_DDL_AUTO:validate}

`localhost:3306`은 백엔드와 MySQL이 같은 개발 PC에서 실행될 때의 주소다. `stockspoon`은 DB 이름이다. `SPRING_DATASOURCE_URL`을 주면 기본 주소를 덮어쓴다. 컨테이너나 AWS에서는 백엔드가 실제 DB에 도달할 수 있는 호스트명을 넣어야 한다. 컨테이너의 `localhost`는 해당 컨테이너 자신을 가리킨다.

로컬 비밀정보 파일 `.env.local`에는 `SPRING_DATASOURCE_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`, `JPA_DDL_AUTO=update`가 들어 있다. Spring Boot와 IntelliJ는 이 파일을 자동으로 읽지 않으므로 실행 설정에 환경변수를 전달해야 한다. 기존 `JWT_SECRET`, 카카오 관련 환경변수도 필요하다.

PowerShell에서 실행할 때는 백엔드 폴더로 이동한 뒤 로컬 파일을 현재 터미널의 환경변수로 불러온다. 기존 인증 기능을 실행하려면 `JWT_SECRET`도 설정해야 한다.

    Get-Content .env.local | ForEach-Object {
      $name, $value = $_ -split '=', 2
      Set-Item -Path "Env:$name" -Value $value
    }
    .\gradlew.bat bootRun
기본 `ddl-auto=validate`는 현재 엔티티와 DB 테이블 구조를 검사한다. 빈 로컬 DB에서 처음 테이블을 만들 때만 `JPA_DDL_AUTO=update`로 실행했다. 운영 DB의 테이블 변경은 검토한 마이그레이션으로 관리해야 하며 `update`에 의존하지 않는다.

## 5. 생성된 테이블

현재 Java 엔티티를 기준으로 JPA가 MySQL에 다음 네 테이블을 생성했다.

| 테이블 | 역할 | 주요 제약 |
|---|---|---|
| `users` | 스톡스푼 사용자 | `user_id` 기본키 |
| `user_oauth` | 카카오 사용자 ID와 내부 사용자 연결 | `user_id` 외래키·유일, `(provider, provider_user_id)` 유일 |
| `refresh_token` | 토큰 해시와 만료 시각 | `user_id` 외래키, `token_hash` 유일 |
| `accounts` | 가상 계좌와 현금 잔액 | `user_id` 외래키, `(user_id, name)` 유일 |

네 테이블 모두 InnoDB와 `utf8mb4`를 사용한다. 생성 직후 네 테이블의 데이터는 모두 0건이었다. 종목, 주문, 체결, AI 판단 테이블은 아직 구현되지 않았다.

## 6. 검증 결과와 남은 검증

- `stockspoon_app`으로 MySQL 8.4.11의 `stockspoon` DB에 접속했다.
- 백엔드를 `JPA_DDL_AUTO=update`로 실행해 네 테이블이 만들어지는 것을 확인했다.
- 백엔드 종료 후에도 네 테이블이 남아 있는 것을 확인했다.
- 기본 `ddl-auto=validate`에서도 백엔드가 정상 시작되는 것을 확인했다.
- `gradlew test`에서 기존 자동 테스트 53개가 통과했다. 이 테스트는 H2를 사용한다.

카카오 로그인·온보딩·계좌 생성의 실제 데이터를 MySQL에 저장한 뒤 재시작 후 다시 조회하는 검증은 아직 수행하지 않았다. MySQL 전용 제약이나 SQL 동작도 H2 테스트만으로 보장되지 않는다.

## 7. AWS 전달 사항

| 변수 | 역할 |
|---|---|
| `MYSQL_DATABASE` | DB 또는 MySQL 컨테이너 초기화 시 사용할 이름: `stockspoon` |
| `MYSQL_USER` | 백엔드 DB 사용자 이름: `stockspoon_app` |
| `MYSQL_PASSWORD` | 운영 환경에서 별도로 정한 앱 DB 비밀번호 |
| `MYSQL_ROOT_PASSWORD` | MySQL 컨테이너의 관리자 비밀번호. 백엔드는 사용하지 않음 |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<DB 호스트>:3306/stockspoon` 형식의 백엔드 접속 주소 |

`MYSQL_DATABASE`와 `MYSQL_ROOT_PASSWORD`는 인프라 구성 방식에 따라 MySQL 서버 초기화에 쓰인다. 백엔드가 직접 읽는 변수는 `SPRING_DATASOURCE_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`다. RDS를 쓰는 경우 DB 생성·관리자 비밀번호 설정 방법은 컨테이너와 다르다. 로컬 비밀번호를 AWS에 재사용하지 않는다. Redis는 이번 변경 범위에 포함되지 않았다.

## 8. ERD와의 차이

[ERD SQL](../../db/stockspoon_erdcloud.sql)은 현재 실행 DB에 적용하지 않았다. 그 설계는 `investment_accounts`, `trading_portfolios`, AI 위임 비율, 종목·주문·체결 등을 포함한다. 현재 코드는 `accounts` 단일 계좌와 AI 위임 여부를 사용한다. 다음 투자 기능을 구현하기 전에 ERD와 코드 중 기준 모델을 팀에서 확정하고 마이그레이션을 작성해야 한다.
