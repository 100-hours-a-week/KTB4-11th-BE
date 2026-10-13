# Execution Application Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 프로젝트에서 BE와 체결 서버를 서로 다른 Application/JAR로 실행한다.

**Architecture:** RiverBeApplication은 공개 API와 내부 체결 HTTP 클라이언트를 구성한다. execution/ExecutionApplication은 내부 체결 API와 시세 기반 체결 작업을 구성한다. 엔티티·저장소·DB/Redis 연결 코드를 재사용하며 각 Application의 빈 구성은 명시적으로 분리한다.

**Tech Stack:** 기존 Java 21, Spring Boot, Gradle, JPA/MySQL, Redis/Valkey, 내부 HTTP.

**Spec:** [체결 서버 분리 설계](2026-10-13-execution-server-design.md). Application 구성은 본 문서가 우선한다. 시장가 중복 방지 테이블 및 결과 미확정 응답은 설계 제안 상태이며 구현 전에 확정한다.

## 범위 및 제약

- 저장소·Gradle 프로젝트를 유지하고 새 원격 저장소/멀티모듈 전환은 하지 않는다.
- 기존 주문/체결 계산, 잠금 순서 및 자산 변경 트랜잭션을 유지한다.
- SQS는 이번 단계에 포함하지 않는다.
- 두 JAR는 공통 클래스를 포함할 수 있다. 실행 빈을 분리하는 것이며 코드 의존성이 완전히 독립된 서비스는 아니다.
- AI 팀 시세 규격은 미확정이다. 기존 MarketStream/Redis 규격을 경계로 유지하고 임의로 새 규격을 정하지 않는다.
- 기존 수집 담당의 운영 경로를 갑자기 제거하지 않는다. AI 팀 시세 서버 연결 검증과 수집 담당 전환은 별도 단계로 인계한다. 체결 Application은 직접 키움 수집을 활성화하지 않는다.

## 목표 배치

```text
com.stock_spoon.river_be/
  RiverBeApplication.java
  config/BeApplicationConfiguration.java
  execution/
    ExecutionApplication.java
    config/ExecutionApplicationConfiguration.java
    config/ExecutionSecurityConfiguration.java
    controller/InternalExecutionController.java
    service/OrderExecutionService.java
    listener/OrderExecutionListener.java
  order/
    service/OrderQueryService.java
    client/ExecutionClient.java
  account/, holding/, report/, market/ ... 기존 공유 코드
```

## Review Focus

- BE가 체결 리스너를 로딩해 두 프로세스에서 체결 작업이 실행되는지.
- 체결 서버가 사용자 API·OAuth 보안 설정을 함께 로딩하는지.
- 요청 타임아웃 후 시장가 재전송으로 주문이 중복 생성되는지.
- 지정가 최초 판단 알림 유실 또는 서버 재시작으로 대기 주문 판단이 누락되는지.
- 다중 Application으로 테스트 구성 탐색 및 Gradle mainClass 추론이 깨지는지.

## Task 1: Application과 빈 구성 분리

**Files:** RiverBeApplication.java 수정, execution/ExecutionApplication.java 및 두 ApplicationConfiguration 추가. 관련 config/market/order 구성 분리.

- [ ] 현재 빈 의존 관계를 따라 공통 저장소·시세 조회, BE API, 체결 작업 목록을 확정한다.
- [ ] 두 Application은 SpringBootConfiguration/EnableAutoConfiguration와 명시적 Import로 구성한다. 루트 전체 자동 탐색을 사용하지 않는다. 필요 구역만 탐색하고 상대 Application 및 그 구성을 제외한다.
- [ ] EntityScan과 EnableJpaRepositories 범위는 명시한다. User/Account/Order/Holding/Execution/Report 등 실제 연관 엔티티를 빠뜨리지 않는다.
- [ ] Redis/IAM·DB·시세 읽기 구성은 두 서버에서 재사용하고, 체결 리스너/체결 서비스는 체결 구성에만 등록한다. BE의 기존 SecurityConfig는 BE에서만 등록한다.
- [ ] execution에는 주문 접수 스케줄 전체를 가져오는 대신 필요한 대기 주문 구독 복구·만료 관리 작업만 등록한다. 접수 중 Redis 수요를 보호하는 기존 순서를 유지한다.
- [ ] Application별 컨텍스트 테스트로 빈 존재/부재를 검증한다. 기존 통합 테스트는 명시적 테스트 구성으로 유지하고 @SpringBootTest의 Application 모호성을 제거한다.

## Task 2: BE의 직접 체결 호출 교체

**Files:** OrderController.java, OrderExecutionListener.java 수정; order/service/OrderQueryService.java, order/client/ExecutionClient.java, execution/controller/InternalExecutionController.java 추가.

**Interfaces:** 지정가 최초 판단 요청은 userId/accountId/orderId, 시장가 요청은 requestId/userId/accountId/주문 입력을 전달한다. 응답은 주문 ID 및 처리 결과로 구성하고 JPA 엔티티를 네트워크에 전달하지 않는다.

- [ ] 리스너의 response(accountId, orderId)를 OrderQueryService로 이동하고 읽기 트랜잭션을 유지한다.
- [ ] 지정가 저장 커밋 후 최초 판단을 내부 HTTP로 전달한다. 실패해도 저장 주문은 유지하고 복구 작업이 다시 판단한다.
- [ ] 시장가 호가 조회·유효성 확인·평가 가격 조회를 체결 서버에 배치하고 기존 자산 변경 트랜잭션에 연결한다.
- [ ] /internal/execution/** 전용 인증, URL 검증, 타임아웃 및 오류 매핑을 구현한다. 빈 키, 잘못된 키, 중복 헤더와 공개 API 접근 거부를 테스트한다.
- [ ] 시장가 내부 중복 방지는 별도 DB 요청 기록과 동일 트랜잭션으로 구현한다. 요청 결과 조회 및 입력 불일치 거부, 응답 유실 후 중복 자산 변경 방지를 검증한다. 이 동작과 스키마는 구현 전 설계 확정 대상이다.

## Task 3: JAR와 Docker 실행 분리

**Files:** build.gradle, Dockerfile, 새 Dockerfile.execution, src/main/resources/application.yaml 및 Application별 설정 파일, 환경변수 예시.

- [ ] 기존 bootJar는 RiverBeApplication을 mainClass로 명시하고 기존 배포가 사용할 JAR 이름을 유지한다.
- [ ] executionBootJar를 추가해 ExecutionApplication 시작점의 별도 *-execution.jar를 만든다. bootRun과 executionBootRun도 시작점을 명시한다.
- [ ] 기존 Dockerfile의 build/libs/*.jar 복사를 정확한 BE JAR 파일명으로 변경한다. 두 JAR가 생성되면 와일드카드 복사가 실패하거나 잘못된 산출물을 선택할 수 있다.
- [ ] Dockerfile.execution은 executionBootJar 결과만 복사한다. BE/체결 이미지를 각각 실행하는 예시를 제공한다.
- [ ] 공통 DB/Redis 설정과 BE 전용 Kakao/JWT 설정을 분리한다. 체결 서버가 OAuth 비밀값 없이 시작되는지 확인한다.
- [ ] 체결 서버 URL·전용 비밀키, DB/Redis 접속값, 포트·타임아웃을 환경변수로 받는다. 동일 호스트 로컬 테스트에서는 BE 8080/체결 8081을 사용하고 컨테이너 포트는 독립 설정한다.
- [ ] CI에서 두 JAR 빌드를 검사한다. 실제 이미지 푸시/배포 경로는 인프라가 지정한 대상과 연결하며 자동으로 새 AWS 리소스를 만들지 않는다.

## Task 4: 통합 검증과 인계

- [ ] 기존 회귀 테스트와 각 Application 컨텍스트/보안 테스트를 실행한다.
- [ ] 별도 프로세스 두 개를 공유 테스트 DB·Redis에 연결해 지정가·시장가·조회·취소를 검증한다.
- [ ] 중복 시장가 요청, 취소/체결 경쟁, 내부 HTTP 실패, 재시작 복구, 오래된 호가와 Redis 장애를 검증한다.
- [ ] Gradle build executionBootJar 및 SpotBugs 통과를 확인한다.
- [ ] 인프라에 두 시작점/JAR/Dockerfile, 환경변수, 내부 통신 경로, SQL 적용 순서와 롤백 절차를 문서로 인계한다.

## 진행 순서와 현재 상태

Application 구성을 분리하면서 호출 연결과 테스트 구성을 함께 완성한 뒤 두 프로세스 검증을 한다. 중간 단계의 BE를 운영에 먼저 배포하지 않는다. 먼저 체결 서버 준비·검증 후 BE 전환을 진행하고, 기존 체결 실행 중단 및 복구 경로를 점검한다.

현재는 계획 문서만 작성했다. 제품 코드·빌드·DB·배포 설정은 변경하지 않았다. 내부 HTTP 분리 방향은 승인되었고, 세부 설계 중 시장가 중복 방지 저장과 결과 미확정 응답은 아직 명시적으로 확정되지 않았다.

## 실행 기록

- 사용자 승인: 별도 execution_requests 테이블 사용 및 스키마 변경 인프라 문서 작성.
- 두 Application/JAR/Docker 설정, 내부 HTTP/인증, 요청 중복 기록, 응답 조회, 대기 주문 복구 구현.
- 구성은 별도 Application의 제한된 탐색 범위와 시작점이 활성화하는 api/execution 프로파일로 분리했다. 기존 테스트는 기본 통합 구성을 유지한다.
- 두 프로세스 실제 HTTP + 공유 H2 검증 및 전체 build/SpotBugs 통과. 상세 결과는 ../../execution-server-infrastructure-handoff.md 참고.
- 계획의 실제 Redis/MySQL/AWS 배포 검증은 외부 환경에서 남아 있다. 미확정 AI 시세 규격을 임의로 구현하지 않았다.
- 제품 코드 구현 및 로컬 검증 완료. SQL은 파일만 제공했으며 배포 DB 적용, 메시지 발송, 커밋/푸시/배포는 하지 않았다.
