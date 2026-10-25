# 주문 내역 조회: 상태·매수/매도 필터와 페이지네이션

## API 계약

`GET /api/v1/accounts/{account_id}/orders` — 기존 로그인 쿠키 인증.
본인 소유 활성 계좌를 조회한다. 계좌의 is_ai_managed와 주문의 source로 필터링하지 않으며, 해당 계좌의 AI·USER 주문을 모두 반환한다. 없는 계좌, 타인 계좌, 비활성 계좌는 404 `ACCOUNT_NOT_FOUND`.
대결 계좌는 CompetitionHistoryAccess.check의 공개 조건도 적용하며, 완료 전 AI 내역은 403 COMPETITION_AI_HISTORY_HIDDEN이다.

| 쿼리 | 생략 시 | 허용 값 |
|---|---|---|
| page | 기존 전체/limit 조회, pagination 필드 생략 | 0 이상 int, 0이 첫 페이지 |
| limit | page 있으면 10, 없으면 전체 | 페이지 조회 1~100, 기존 조회 양의 int |
| order_side | 전체 매수·매도 | buy 또는 sell |
| order_status | 전체 상태(대기·체결·취소) | pending, executed, cancelled |

숫자 쿼리의 빈 값, 음수, 소수, 문자, int 범위 초과 및 지원하지 않는 order_side/order_status는 400 `INVALID_ORDER_QUERY`.
`order_side=all` 대신 전체 탭은 order_side를 생략한다. page 없는 요청에서도 order_side 필터는 적용된다.

```http
# 홈: 최신 체결 주문 최대 3개. 기존 FE 요청에 order_status=executed 추가 필요
GET /api/v1/accounts/2/orders?limit=3&order_status=executed

# 기존 전체 조회
GET /api/v1/accounts/2/orders

# 전체 첫 페이지
GET /api/v1/accounts/2/orders?page=0&limit=10

# 매수 두 번째 페이지
GET /api/v1/accounts/2/orders?order_side=buy&page=1&limit=10

# 매도 첫 페이지, 기본 10개
GET /api/v1/accounts/2/orders?order_side=sell&page=0

# 대기 매수 첫 페이지
GET /api/v1/accounts/2/orders?order_status=pending&order_side=buy&page=0&limit=10

# 체결 완료 두 번째 페이지
GET /api/v1/accounts/2/orders?order_status=executed&page=1&limit=10

# 취소 주문 첫 페이지
GET /api/v1/accounts/2/orders?order_status=cancelled&page=0&limit=10
```

## 응답

기존 요청은 `account_id`, `orders`만 반환한다. page가 있는 요청에는 아래 pagination을 추가한다.
주문과 체결의 기존 필드명 및 null은 유지한다. average_price는 복수 체결 평균을 표현하기 위해 소수 4자리 HALF_UP의 JSON 숫자가 된다(예: 110.5000).

```json
{
  "account_id": 2,
  "orders": [],
  "pagination": {
    "page": 3,
    "limit": 10,
    "total_elements": 23,
    "total_pages": 3,
    "has_next": false
  }
}
```

위는 범위 밖 페이지 예시다. 전체 개수는 선택 계좌·상태·매수/매도·종목코드 필터에 맞는 **주문 수**다.
order_status=executed이면 마지막 체결시각 내림차순, 그 외에는 주문 생성시각 내림차순이다. 동률은 주문 ID 내림차순이다. 하나의 주문이 여러 번 체결되어도 카드/페이지 항목은 한 건이다.
빈 결과는 orders=[], total_elements=0, total_pages=0, has_next=false다. 범위 밖 page는 전체 개수를 유지하면서 빈 배열을 반환한다.
JPA offset은 int 범위만 지원하므로 실제 데이터 범위 안이면서 offset이 int를 넘는 요청은 400이다.
새 체결이 추가되면 이후 페이지의 위치가 이동할 수 있다. 페이지 간 고정 스냅샷을 보장하지 않는다.

## 데이터와 집계

| 필드 | 출처 및 처리 |
|---|---|
| 주문 필드 | Order 저장값. order_status는 pending/executed/cancelled |
| can_cancel | 조회 시 PENDING이면 true, 그 외 false. 취소 API에서 상태를 다시 검사하므로 조회 후 체결되면 취소가 거절될 수 있다 |
| stock_name | KiwoomMarketClient.stockDetails. 반환 대상의 같은 종목은 요청당 한 번 조회 |
| executions | 해당 주문의 모든 Execution, 체결시각/체결 ID 오름차순. 대기·취소의 체결 없음은 [] |
| execution_summary | 체결 기록이 없으면 null. 체결이 있는 경우 아래 기준으로 집계 |
| 요약 quantity | 모든 체결 수량 합계 |
| 요약 total_amount | 모든 체결 가격 × 수량 합계 |
| 요약 average_price | total_amount / quantity, 소수 4자리 HALF_UP |
| 요약 executed_at | 주문의 마지막 체결 시각, +09:00 |
| 매수 요약 손익·수익률 | null |
| 단일 매도 요약 손익·수익률 | 기존 저장값 유지 |
| 복수 매도 요약 손익 | 모든 체결의 실현손익 합계. 하나라도 누락되면 null |
| 복수 매도 요약 수익률 | 합산 손익 / (합산 금액 - 합산 손익) × 100, 소수 4자리 HALF_UP. 손익 누락 또는 원가가 양수가 아니면 null |
| reason.summary | 연결된 AiOrderReport.reason 원문. 리포트가 없으면 명시적 null |

복수 매도의 원가 역산은 기존 AI 리포트 계산과 같다. 저장 손익의 소수 2자리 정밀도에 한정된다.
이 문서는 현재 로컬 구현의 연동 계약이다. 공유 API 시트와 배포 서버 반영 여부는 별도로 확인해야 한다.

## 구현 흐름과 오류 처리

1. OrderController.list가 limit/page/order_side/order_status/stock_code를 받아 JWT subject에서 변환한 userId 및 accountId와 함께 OrderHistoryService.list에 전달한다.
2. 서비스에서 입력을 검증한 후 읽기 전용 트랜잭션 안에서 계좌 소유권·활성 상태를 확인한다.
3. 페이지 요청은 같은 필터 조건으로 전체 주문 수를 조회한다. offset이 전체 범위 밖이면 빈 배열로 끝낸다.
4. OrderRepository.findHistory가 계좌·상태·매수/매도·종목코드 조건을 적용하고 DB에서 페이지를 제한한다. LEFT JOIN / GROUP BY로 복수 체결의 주문 중복을 방지한다. executed 필터에서는 마지막 체결시각, 나머지는 생성시각으로 정렬한다. countHistory도 동일 필터로 주문만 센다.
5. 선택된 주문 ID의 체결을 ExecutionRepository.findForOrders로 일괄 조회하고 DTO로 복사한다. 체결이 없는 EXECUTED 주문은 500 INVALID_ORDER_DATA다. 페이지 요청은 해당 페이지의 주문만 검증하며, page 없는 요청은 기존처럼 모든 필터 후보를 검증한 뒤 limit을 적용한다.
6. snapshot은 체결이 있는 주문만 summarize로 집계하고, 없는 대기·취소 주문의 요약은 null로 만든다. 트랜잭션 밖에서 반환할 종목명만 조회한다. 외부 실패는 503 ORDER_DATA_UNAVAILABLE이며 일부 성공 결과로 숨기지 않는다.
7. page 요청에만 pagination을 붙여 반환한다.

토큰·근거 원문·외부 응답 원문은 로그에 기록하지 않는다.

```text
GET /api/v1/accounts/{accountId}/orders?order_status=pending&page=0&limit=10
  → order.controller.OrderController.list(jwt, accountId, limit, page, orderSide, orderStatus, stockCode)
  → order.service.OrderHistoryService.list(userId, accountId, limit, page, orderSide, orderStatus, stockCode)
    → parseStatus(orderStatus): null / PENDING / EXECUTED / CANCELLED
    → 읽기 전용 트랜잭션 안: 계좌 소유권 확인 → CompetitionHistoryAccess.check(userId, accountId, false)
    → OrderRepository.countHistory(accountId, selectedStatus, selectedSide, stockCode): 페이지 요청일 때
    → OrderRepository.findHistory(accountId, selectedStatus, selectedSide, stockCode, pageable)
    → ExecutionRepository.findForOrders(선택된 주문 ID 목록)
    → snapshot(order, fills) → 체결이 있으면 summarize(order, fills)
    → DB 읽기 트랜잭션 종료
    → KiwoomMarketClient.stockDetails(stockCode): 반환 대상 종목명 조회
  → OrderHistoryResponse(accountId, orders, pagination): 200 JSON
```

요청의 order_status는 서비스의 parseStatus에서 enum으로 변환되며, 생략 시 null이 Repository에 전달되어 상태 제한이 해제된다. 목록 조회와 개수 조회에는 같은 상태·매수/매도·계좌·종목코드 조건이 전달된다. 반환 주문에 체결이 없을 때는 대기·취소라면 정상 응답하고, EXECUTED이면 데이터 오류로 종료한다. 종목명 외부 조회는 DB 트랜잭션이 끝난 후 실행한다.

관련 코드: [OrderController](../src/main/java/com/stock_spoon/river_be/order/controller/OrderController.java), [OrderHistoryService](../src/main/java/com/stock_spoon/river_be/order/service/OrderHistoryService.java), [OrderRepository](../src/main/java/com/stock_spoon/river_be/order/repository/OrderRepository.java), [OrderHistoryResponse](../src/main/java/com/stock_spoon/river_be/order/dto/OrderHistoryResponse.java).

## FE 변경 안내

- 전체 보기 진입: page=0으로 호출하고 orders 표시.
- 전체/매수/매도 및 상태 변경: page=0으로 초기화하고 order_side/order_status를 바꿔 재호출. FE가 현재 페이지 배열에서 다시 필터링하지 않는다.
- 페이지 이동: 선택한 order_status, order_side와 limit을 유지하고 page만 변경한다.
- total_pages로 번호 버튼, has_next로 다음 버튼을 제어한다. 화면 페이지 번호는 API page + 1이다.
- 빠르게 탭/페이지를 전환할 때 이전 요청 응답이 새 목록을 덮어쓰지 않도록 취소하거나 최신 요청만 반영한다.
- 홈 및 기존 체결 전용 화면은 order_status=executed를 명시해야 한다. 생략하면 대기·취소까지 포함하며 정렬 기준도 생성시각으로 바뀐다. 판단 근거 보기는 기존 order_id를 그대로 사용한다.

## 검증

OrderHistoryTests는 기존 JSON 구조/홈 limit/정렬/인증/접근권한/외부 실패와 페이지 구간, 필터별 개수, 복수 체결, 평균가격 정밀도, 누락 손익, 빈 페이지, 잘못된 입력을 검증한다.

```powershell
.\gradlew.bat test --tests '*OrderHistoryTests' --no-daemon
.\gradlew.bat test spotbugsMain --no-daemon
```

자동 검증은 H2와 모의 키움 응답을 사용한다. 실제 FE 브라우저, 운영 MySQL, 실제 키움 호출은 검증 범위에 포함하지 않는다.

## 2026-10-25 변경: 주문 출처 필터 제거

OrderHistoryService.list()에서 계좌의 is_ai_managed로 source를 결정하던 부분을 제거했다. OrderRepository.findHistory()/countHistory()에서도 `and o.source = :source`와 source 매개변수를 제거했다. 주문 생성 시 계좌 운용 유형 검증은 그대로 유지한다.

조회 요청이 들어오면 order.controller.OrderController.list()는 인증된 JWT subject를 userId로 변환하고, URL의 accountId 및 검색 조건을 order.service.OrderHistoryService.list()에 전달한다. 조회에서는 JWT actor를 주문 출처 필터로 사용하지 않는다. 서비스는 사용자 소유의 활성 계좌인지 확인한 뒤 CompetitionHistoryAccess.check()로 대결 공개 조건을 검사한다. 완료 전 AI 대결 계좌는 기존대로 403 COMPETITION_AI_HISTORY_HIDDEN이며, 출처 필터 제거로 이 제한을 우회할 수 없다.

Repository는 accountId에 속한 주문 중 요청한 상태·매수/매도·종목코드가 맞는 주문을 가져온다. 계좌 운용 유형과 주문 출처가 달라도 제외하지 않는다. 페이지 요청이면 동일 조건으로 전체 주문 수를 센 뒤 페이지 범위의 주문을 읽는다. 상태를 생략하면 모든 상태가 후보이고, pending을 지정하면 대기 주문만 후보이다. 모든 출처를 포함한다는 것은 다른 검색 조건이나 페이지 제한까지 해제한다는 뜻이 아니다.

선택한 주문의 체결은 ExecutionRepository.findForOrders()로 일괄 조회한다. snapshot()은 DB에 저장된 주문 출처를 응답 order_source에 그대로 기록하며, 대기·취소 주문은 체결이 없어도 정상 반환한다. 체결 상태인데 체결 데이터가 없으면 기존대로 500 INVALID_ORDER_DATA다. DB 읽기 트랜잭션 종료 후 KiwoomMarketClient.stockDetails()로 종목명을 구성한다. 조회 실패는 503 ORDER_DATA_UNAVAILABLE이며, 빈 목록으로 숨기지 않는다. 마지막으로 주문 목록과 페이지 정보를 200 응답으로 반환한다.

예: 계좌 772가 AI 운용이어도, 그 계좌에 source=USER인 대기 주문이 있다면 `order_status=pending` 조회에 포함한다. 실제 주문 4156의 DB 행·상태·계좌 연결과 배포 서버 반영 여부는 별도로 확인해야 한다.

검증은 OrderHistoryTests에서 AI·수동 계좌 각각에 AI·USER 주문을 함께 저장한 뒤 목록·원래 출처·상태/매수매도/종목 필터·전체 개수·페이지 범위 밖 응답을 확인한다. 기존 반대 출처 제외 기대값도 새 정책에 맞춰 수정했다. 운영 DB를 변경하거나 주문 출처를 다시 저장하는 마이그레이션은 필요하지 않다.

검증 결과(2026-10-25): OrderHistoryTests 23개, OrderControllerTests 38개, QueuedOrderControllerTests 2개, CompetitionTradeHistoryTests 12개로 총 75개 통과·실패 0개·건너뜀 0개. 변경 전 혼합 출처 재현 테스트 실패를 확인한 뒤 필터를 제거했다. 운영 DB의 주문 4156은 조회하지 않았고 실제 서버 배포·커밋·푸시는 수행하지 않았다.
