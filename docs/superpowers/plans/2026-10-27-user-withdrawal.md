# 회원 탈퇴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 한 번의 요청으로 카카오 연결을 해제하고 회원과 모든 계좌·주문·대결 데이터를 Hard Delete한다. 재가입은 새 회원으로 처리한다.

**Architecture:** UserController → UserWithdrawalService → KakaoClient 및 UserDeletionRepository. DB 삭제는 하나의 트랜잭션, Redis 정리는 커밋 후 수행한다. 탈퇴 작업 테이블·상태 필드·전용 스케줄러·202 응답은 추가하지 않는다.

**Tech Stack:** 기존 Spring/JPA, 관계형 DB, Redis/SQS, RestClient.

**Spec:** 이 대화에서 확정한 Hard Delete·카카오 연결 해제·신규 재가입 정책과 단순한 동기 처리 요청.

## Global Constraints

- 분석 기준은 저장된 origin/dev 8e29246f29c4e29c91c5b06e948620c5f93e89e5. 구현 전 최신 dev와 실제 DDL을 비교한다.
- JWT에서 사용자 ID를 얻고 CSRF 검증을 유지한다. 비활성 계좌를 포함한 모든 소유 계좌가 삭제 대상이다.
- 다른 회원과 공용 종목·시세는 유지한다. Admin 키는 서버 비밀 설정으로 주입한다.
- 카카오 API와 DB는 함께 롤백할 수 없다. 자동 복구 시스템은 이번 범위에서 제외한다.

## 변경 파일

패키지 기준: src/main/java/com/stock_spoon/river_be/.

| 구분 | 파일 | 역할 |
|---|---|---|
| 수정 | user/controller/UserController.java | DELETE /api/v1/users/me, 성공 후 쿠키 삭제 및 204 반환 |
| 추가 | user/service/UserWithdrawalService.java | withdraw(long userId): 회원 확인, 잠금, 연결 해제, DB 삭제 총괄 |
| 추가 | user/repository/UserDeletionRepository.java | deleteAllForUser(long userId): FK 순서에 맞는 명시적 삭제 쿼리 |
| 수정 | user/repository/UserRepository.java | 회원 잠금 조회 |
| 수정 | auth/client/KakaoClient.java | unlink(long providerUserId): Admin 키로 연결 해제 |
| 수정 | config/KakaoProperties.java, src/main/resources/application.yaml | Admin 키 설정 |
| 수정 | config/SecurityConfig.java | 사용자 JWT 인증 후 회원 존재 검사, AI 전용 인증 유지 |
| 보완 | auth/service/KakaoAuthService.java, AuthService.java, RefreshTokenService.java | 탈퇴와 로그인·토큰 발급 경쟁 방지 |
| 수정 | order/messaging/OrderRequestService.java, OrderRequestTransactions.java | 삭제 계좌의 지연 메시지 정상 종료, DB 장애는 재시도 |
| 수정 | order/reservation/OrderReservationStore.java 및 구현체 | removeAll(long accountId): 계좌 예약 정리 |
| 보완 | account/service/AccountService.java, order/service/OrderService.java, execution/service/OrderExecutionService.java, competition/service의 생성·시작·정산·성과·리포트 저장 경로 | 기존 잠금 및 대상 존재 재확인으로 삭제와 쓰기 경쟁 조율 |

기존 AuthCookieService와 OrderSubscriptionService를 재사용한다. 기본 신규 클래스는 탈퇴 서비스와 삭제 Repository 두 개다.

## 실행 순서

1. Controller가 JWT의 회원 ID를 전달한다.
2. Service의 DB 트랜잭션에서 회원을 잠금 조회하고 OAuth 식별자 및 모든 소유 계좌·대결 ID를 확인한다. 중복 요청도 잠금 후 최신 DB 상태를 재확인한다.
3. 기존 대결 정산과 일관되게 대결 ID 순 → 계좌 ID 순으로 잠근다. 회원 잠금은 생성·로그인 경로와 함께 설계하고 역순 잠금이 생기지 않도록 확인한다.
4. KakaoClient.unlink를 제한된 연결/응답 타임아웃으로 호출한다. 별도 작업 상태 없이 중복 탈퇴를 직렬화하기 위해 첫 구현은 호출 동안 DB 잠금을 유지하는 비용을 수용한다.
5. 카카오 성공 또는 공식적으로 연결 없음이 확인된 경우에만 DB 삭제를 수행한다. 일반 4xx/5xx나 타임아웃을 성공으로 간주하지 않는다.
6. UserDeletionRepository에서 자식 데이터부터 삭제하고 커밋한다.
7. 커밋 후 계좌별 Redis 예약과 시세 구독 수요를 정리한다. 실패하면 기존 주문 reconcile에서 없는 계좌의 예약을 정리한다. DB 삭제 성공을 Redis 장애 때문에 롤백했다고 응답하지 않는다.
8. Controller가 인증 쿠키를 지우고 204 No Content를 반환한다.

삭제 순서:
- AI 리포트 상세 → AI 리포트·체결 → 주문 → 보유 종목.
- 일별 성과 → 대결 참가자·분석·판정 → 대결.
- 주문 요청 이력·관심 종목 등록·Refresh Token → 계좌 → OAuth → 회원.

주문은 체결 여부와 관계없이 최종 삭제한다. 보존하지 않을 주문에 취소 상태를 저장하는 절차는 필수로 추가하지 않는다. 삭제가 먼저 잠금을 얻으면 후속 체결은 대상 부재로 종료하고, 체결이 먼저 끝나면 그 결과까지 삭제한다.

## 실패 처리

- 카카오 호출 실패: DB 삭제 없이 오류 반환. 쿠키를 유지해 재요청 가능하게 한다.
- 카카오 성공 후 DB 실패: DB만 롤백되며 카카오 연결은 해제된 상태다. 오류를 기록하고 재요청 시 연결 없음도 처리한다. 자동 복구를 보장하지 않는다.
- 카카오 응답 유실: 성공으로 응답하지 않고 재요청으로 확인한다.
- 삭제된 회원/계좌의 SQS 메시지: DB에서 부재를 확인하면 주문이나 요청 이력을 다시 저장하지 않고 ACK한다. DB 조회 장애는 재시도한다.
- Redis의 늦은 publish 기록: 기존 reconcile에서 계좌 부재를 확인해 예약을 제거하며 재발행하지 않는다.
- 회원 존재 검사는 HTTP 접근뿐 아니라 실제 쓰기 트랜잭션에서도 필요하다. 삭제 직전 시작된 생성·로그인·토큰 발급도 잠금 후 재확인한다.
- 대결 스케줄러 및 리포트 저장은 삭제와 경쟁해 고아 데이터를 만들지 않아야 한다. 이미 삭제된 대상은 정상 종료한다.
- 오래된 중복 탈퇴가 재가입한 회원의 연결을 끊지 않도록, 이전 userId의 잠금 조회가 성공한 경우에만 unlink한다.

## Review Focus

- 카카오 성공 후 DB 실패 및 응답 유실 후 재요청.
- 탈퇴와 계좌 생성·체결·대결 정산의 동시 실행.
- 삭제 후 SQS 메시지 및 Redis 기록 도착.
- 로그인·토큰 발급과 탈퇴 경쟁.
- 재가입 후 오래된 탈퇴 요청의 영향.

## 구현 및 검증 순서

### Task 1: DB 삭제
- [x] 연관 데이터가 모두 있는 회원과 무관한 다른 회원 fixture로 삭제 테스트를 작성한다.
- [x] UserDeletionRepository와 잠금 조회를 구현한다. bulk SQL은 JPA cascade에 의존하지 않는다.
- [x] 전체 삭제, 다른 회원/공용 데이터 보존, 중간 SQL 실패 시 전체 롤백을 검증한다.
- [x] H2 FK/잠금 환경에서 계좌 생성과 탈퇴 경쟁 및 삭제 후 체결·대결 작업 종료를 확인했다. 운영 MySQL 동시 실행 검증은 별도다.

### Task 2: 동기 API 및 카카오
- [x] KakaoClient.unlink를 HTTP 대역으로 성공·연결 없음·타임아웃·설정 오류 테스트한다.
- [x] Service와 Controller를 구현하고 204, 인증/CSRF 실패, 중복 요청의 상태 재확인을 검증한다.
- [x] 카카오 성공 후 DB 실패 및 응답 유실 후 재요청을 검증한다. 쿠키는 성공 시에만 삭제한다.

### Task 3: 메시지·예약 정리
- [x] 삭제 전후 SQS 중복 메시지와 삭제 후 Redis 기록을 재현한다.
- [x] 소비자와 기존 reconcile을 보완하고 consume 반환값 사용처도 함께 수정한다.
- [x] 대상 부재는 정상 종료, DB 장애는 재시도, Redis 장애 후 기존 reconcile에서 복구됨을 검증한다.

### Task 4: 인증 및 재가입
- [x] 기존 Access/Refresh Token 접근 거절 및 로그인·재발급 경쟁을 검증한다.
- [x] 같은 카카오 식별자로 재가입 시 새 userId, onboardingCompleted=false, 이전 이력 없음 확인.
- [x] 오래된 중복 탈퇴 요청이 신규 회원 연결을 해제하지 않는지 검증한다.
- [x] 관련 Gradle 테스트 후 전체 테스트를 실행하고 docs/stockspoon_login_auth_flow.md에 204 응답과 실패 의미를 반영한다.

## 현재 상태

제품 코드와 문서 작성을 완료했다. 전체 테스트 483개 중 465개 통과, 18개 건너뜀, 실패 0개이며 SpotBugs도 통과했다. 실제 동작 문서는 docs/stockspoon_user_withdrawal.md를 기준으로 한다. 실제 회원 데이터나 카카오 연결은 변경하지 않았다.
