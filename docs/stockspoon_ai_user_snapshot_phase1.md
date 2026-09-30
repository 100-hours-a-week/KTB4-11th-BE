# AI 서버용 전체 사용자 정보 조회: 1단계

## 이번 단계의 범위

`GET /api/v1/users/ai-server`의 DB 조회 부분을 준비한다. HTTP 경로, AI 서버 인증,
대기 주문 종목의 `current_stock_price` 조회 및 최종 JSON 변환은 아직 연결하지 않았다.

모든 사용자를 포함한다. 사용자에게 활성 AI 관리 계좌가 없으면 `accounts`는 빈 목록이다.
계좌는 `is_active=true`와 `is_ai_managed=true`를 모두 만족할 때만 포함한다.
해당 계좌의 보유종목과 `PENDING` 주문만 읽는다.

## 이름 변경

`accounts.ai_delegated`를 `accounts.is_ai_managed`로 바꾸고 Java 필드·메서드와
기존 계좌 API 응답 필드도 같은 의미의 이름으로 맞췄다. 주문 가능 계좌 검사도
`isAiManaged()`를 사용한다. 기존 계좌 API를 사용하는 FE는 `ai_delegated` 대신
`is_ai_managed`를 읽도록 변경해야 한다.

로컬 MySQL 8.4.11 확인 시 두 컬럼이 모두 `bit(1) NOT NULL`로 존재했고 `accounts`는
0행이었다. 확인 후 중복된 `ai_delegated` 컬럼을 삭제했으며, 변경 후
`is_ai_managed`만 남고 0행인 것을 재확인했다. 이 로컬 상태에 해당하는 SQL은
`db/drop_redundant_ai_delegated_local.sql`이다. 기존 컬럼만 있는 다른 DB에는
`db/rename_ai_managed.sql`을 적용하기 전 실제 컬럼 상태와 데이터를 확인해야 한다.

## 데이터 조회

`AiUserSnapshotService.snapshot()`이 모든 사용자, 활성 AI 관리 계좌, 해당 계좌의
보유종목 및 `PENDING` 주문을 조회해 사용자 ID와 계좌 ID로 묶는다. 계좌가 없는
사용자도 결과에 남긴다. 보유종목과 주문은 계좌별 반복 조회 대신 계좌 ID 목록으로
조회한다. 현금은 `accounts.cash_balance`, 취득원가는 `holdings.total_cost`를 사용한다.

## 확인한 결과

H2 테스트에서 여러 사용자, 계좌가 없는 사용자, 비활성·AI 비관리 계좌의 제외,
보유종목 및 `PENDING` 주문만 포함하는 경우를 확인했다. 기존 계좌 API 테스트와
주문 서비스·컨트롤러 테스트도 통과했다. 실제 MySQL에서는 컬럼 상태와 행 수를
변경 전후에 확인했다.
