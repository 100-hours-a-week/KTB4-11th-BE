# Order Package Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** order의 기존 25개 Java 파일을 역할별 하위 패키지로 정리하여 account/auth와 탐색 방식을 통일한다.

**Architecture:** order라는 기능 경계 안에 entity/controller/service/repository/dto/exception을 두고 listener와 validator를 구분한다. 클래스 책임과 실행 흐름은 유지하며 package/import 및 필요한 접근 범위만 수정한다.

**Tech Stack:** 기존 Java, Spring Boot, Spring Data JPA, Gradle, JUnit.

**Spec:** 사용자의 order 구조 정리 요청과 아래 이동표. 확인 기준 브랜치: feat/106-migrate-in-memory-to-redis.

## Global Constraints

- API URL, 응답 JSON, DB 테이블/컬럼, Redis 키/채널, 주문 및 체결 정책을 유지한다.
- 기존 account/auth 명명에 맞춰 domain 대신 entity를 사용한다.
- 클래스명과 기존 메서드 시그니처는 유지하되 패키지 분리 때문에 필요한 메서드만 public으로 조정한다.
- Holding과 AI 보고서의 다른 기능 패키지 이전, 서비스 책임 재분할, 새 인터페이스 도입은 범위에서 제외한다.
- 커밋과 푸시는 별도 요청이 있을 때 진행한다.

## 초기 이동표 (하단 최종 기능별 이동표로 대체됨)

기준 경로: src/main/java/com/stock_spoon/river_be/order/. 아래 클래스는 현재 이 경로 바로 아래에 있으며 대상 폴더로 이동한다.

| 대상 폴더 | 클래스(.java) |
| --- | --- |
| controller | OrderController, AiReportController |
| service | OrderService, OrderExecutionService, OrderSubscriptionService, OrderHistoryService, OrderBuyPriceService, AiReportService |
| entity | Order, Execution, Holding, AiOrderReport, ReportReasoning |
| repository | OrderRepository, ExecutionRepository, HoldingRepository |
| dto | OrderCreateRequest, OrderCancelRequest, OrderCreateResponse, OrderHistoryResponse, AiReportResponse |
| exception | OrderException, OrderExceptionHandler |
| listener | OrderExecutionListener |
| validator | OrderMarketValidator |

ReportReasoning은 @Embeddable인 JPA 값 타입이므로 entity에 배치한다. Order의 내부 enum과 DTO의 내부 record는 기존 클래스 안에 유지한다.

## Review Focus

- 패키지 전용 메서드: DTO 생성/검증과 보고서 갱신을 다른 패키지의 서비스가 호출할 수 있어야 한다.
- 주문/체결 트랜잭션: 리스너와 체결 서비스 간 기존 Spring 프록시 호출 및 잠금 동작을 유지해야 한다.
- 이벤트/구독: Spring 이벤트 수신과 Redis 시세 구독 갱신이 유지되어야 한다.
- JPA 매핑: 엔티티 이름과 연관관계, JPQL 조회 결과를 유지해야 한다.
- 외부 참조: account/user/market 및 통합 테스트의 import와 전체 클래스명 참조를 모두 수정해야 한다.

## Task 1: 운영 코드와 테스트 패키지를 함께 정리

**Files:** 위 이동표의 모든 파일과 src/test/java/com/stock_spoon/river_be/order/*.java. 외부 직접 참조는 account/service/AccountService.java, account/service/AccountHoldingsService.java, user/service/AiUserSnapshotService.java, market/RedisMarketStream.java 및 해당 테스트에서 확인되었다. 프로젝트 전체 검색으로 추가 참조도 함께 갱신한다.

**Interfaces:** 기존 공개 클래스/메서드의 기능은 유지하고 정규 클래스명만 order.<역할>.<클래스명>으로 바뀐다.

- [x] 작업 브랜치/변경 상태와 AGENTS.md를 다시 확인하고 기존 테스트 결과를 확보한다. 기존 실패가 있으면 이동으로 생긴 실패와 구분한다.
- [x] 이동표대로 파일을 이동하고 package 및 import를 수정한다. 문자열 형태의 정규 클래스명, 테스트 어노테이션과 문서의 활성 소스 경로도 검색한다.
- [x] 다른 패키지에서 호출하는 OrderCreateRequest.validateReportInputs, OrderCreateResponse.from, AiReportResponse.from의 기존 오버로드, AiOrderReport.recordHoldingWeight에 필요한 public 접근을 부여한다. 그 외 접근 오류도 호출 근거를 확인해 최소 수정한다.
- [x] 테스트를 대상 클래스 패키지에 맞춘다: HoldingTests/ExecutionTests는 entity, OrderControllerTests는 controller, OrderMarketValidatorTests는 validator, OrderExecutionListenerTests/OrderExecutionEventTests는 listener, 나머지 주문 서비스/트랜잭션 테스트는 service로 이동한다. 실제 package 전용 호출이 있는 경우 그 소유 클래스 패키지에 우선 배치한다. 테스트용 Clock 생성자나 timeout 오버로드를 편의상 public으로 열지 않는다.
- [x] .\gradlew.bat compileJava compileTestJava 실행: 운영 코드와 테스트가 모두 컴파일되어야 한다.

## Task 2: 회귀 검증 및 변경 범위 확인

**Files:** 이동된 기존 테스트와 account/user/market 관련 테스트. 새 동작을 추가하지 않으므로 구조만 확인하는 형식적인 테스트는 추가하지 않는다.

- [x] .\gradlew.bat test --tests 'com.stock_spoon.river_be.order.*' --tests 'com.stock_spoon.river_be.execution.*' --tests 'com.stock_spoon.river_be.holding.*' --tests 'com.stock_spoon.river_be.report.*' 실행: 주문 생성/취소, 체결, 이력, 호가단위, 보고서, 이벤트 및 구독 관련 기존 검증이 통과해야 한다.
- [x] .\gradlew.bat build --build-cache 실행: 전체 테스트 및 프로젝트 빌드 검사를 통과해야 한다. 외부 서비스가 필요한 테스트는 기존 설정에 따르며 실행/미실행 내역을 구분해 보고한다.
- [x] git diff --check와 git diff --stat, 이름 변경 포함 diff를 확인하여 비즈니스 로직/설정/스키마 변경이 섞이지 않았는지 검토한다.
- [x] 변경된 구조, 접근 범위 조정, 테스트 결과를 보고한다.

## 실행 기록

- 기존 작업 브랜치에서 진행. 이동 전 주문 테스트 통과.
- 운영 코드 25개와 테스트 13개 이동, 외부 참조와 JPQL enum 전체 경로 및 문서 경로 수정.
- OrderControllerTests와 OrderExecutionEventTests는 package 전용 Clock 생성자를 사용하므로 service에 배치. 테스트용 생성자의 공개 범위는 유지.
- compileJava/compileTestJava 및 이동 후 주문 테스트 통과.
- 원본과 정규화 비교 결과 package/import 및 계획한 접근 범위 이외의 로직 차이 없음. 별도 리뷰에서도 동작 변경 없음 확인; 지적된 테스트 문서 링크 수정.
- 전체 build --build-cache 성공: 테스트 290개 중 269개 통과, 21개 건너뜀, 실패/오류 0. bootJar와 spotbugsMain 통과. 외부 연동 테스트는 기본 실행 설정에 따라 일부 건너뛰었으며 실제 배포 환경 검증은 수행하지 않음.
- git diff --check 통과. 커밋/푸시 없음.

## 최종 변경: 기능 도메인 분리

사용자 확인: 체결 기능은 추후 시세 처리 부하와 독립 확장을 위해 별도 서버로 분리한다. DB는 공유한다. 이번 작업은 패키지 구조 변경만 수행하며 실행 프로세스, 트랜잭션, API 및 스키마는 유지한다. 이 결정은 위의 초기 order 하위 역할별 이동표를 대체한다.

| 기능/역할 | 클래스 |
| --- | --- |
| order/controller | OrderController |
| order/service | OrderService, OrderSubscriptionService, OrderHistoryService |
| order/entity | Order |
| order/repository | OrderRepository |
| order/dto | OrderCreateRequest, OrderCancelRequest, OrderCreateResponse, OrderHistoryResponse |
| order/exception | OrderException, OrderExceptionHandler |
| order/validator | OrderMarketValidator |
| execution/service | OrderExecutionService, OrderBuyPriceService |
| execution/listener | OrderExecutionListener |
| execution/entity | Execution |
| execution/repository | ExecutionRepository |
| holding/entity | Holding |
| holding/repository | HoldingRepository |
| report/controller | AiReportController |
| report/service | AiReportService |
| report/entity | AiOrderReport, ReportReasoning |
| report/dto | AiReportResponse |

OrderSubscriptionService는 주문 접수 및 대기 주문의 시세 수요를 관리하므로 order에 유지한다. OrderBuyPriceService는 체결 시 보유 자산 평가에 필요한 가격을 조회하므로 execution에 둔다. Holding은 현재 독립 API가 없으므로 entity/repository만 둔다. 체결 서비스는 기존처럼 주문, 계좌, 보유 저장소를 직접 이용한다. 패키지 분리는 서버 간 통신 분리와 다르며 추후 서버 분리 시 그 경계를 별도로 설계한다.

테스트도 대상 기능으로 이동한다. OrderControllerTests는 주문 서비스의 package 전용 Clock 생성자를 사용하므로 order/service에 유지한다. 별도 execution/service/ExecutionTestFixture를 통해 테스트 시각을 고정하여 운영 생성자를 public으로 변경하지 않는다.

- [x] 파일 재배치와 외부 참조 및 JPQL enum 경로 수정.
- [x] 문서 소스 링크와 테스트 실행 경로 갱신.
- [x] 운영/테스트 컴파일 통과.
- [x] 원본 비교: package/import, 기존 승인된 접근 범위, 테스트 fixture 호출 외 업무 로직 차이 없음.

- [x] 최종 기능 패키지 기준 전체 build --build-cache 및 SpotBugs 통과. 테스트 290개, 실패 0개, 건너뜀 21개. 커밋/푸시 없음.
