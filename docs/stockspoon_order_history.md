# 체결 완료 AI 주문 내역 조회

## API 계약

`GET /api/v1/accounts/{account_id}/orders` — 기존 로그인 쿠키 인증.
본인 소유 활성 계좌의 `AI` / `EXECUTED` 주문만 반환한다. 없는 계좌, 타인 계좌, 비활성 계좌는 404 `ACCOUNT_NOT_FOUND`.

| 쿼리 | 생략 시 | 허용 값 |
|---|---|---|
| page | 기존 전체/limit 조회, pagination 필드 생략 | 0 이상 int, 0이 첫 페이지 |
| limit | page 있으면 10, 없으면 전체 | 페이지 조회 1~100, 기존 조회 양의 int |
| order_side | 전체 매수·매도 | buy 또는 sell |

빈 값, 음수, 소수, 문자, int 범위 초과 및 지원하지 않는 order_side는 400 `INVALID_ORDER_QUERY`.
`order_side=all` 대신 전체 탭은 order_side를 생략한다. page 없는 요청에서도 order_side 필터는 적용된다.

```http
# 홈: 최신 체결 주문 최대 3개, 기존 응답 유지
GET /api/v1/accounts/2/orders?limit=3

# 기존 전체 조회
GET /api/v1/accounts/2/orders

# 전체 첫 페이지
GET /api/v1/accounts/2/orders?page=0&limit=10

# 매수 두 번째 페이지
GET /api/v1/accounts/2/orders?order_side=buy&page=1&limit=10

# 매도 첫 페이지, 기본 10개
GET /api/v1/accounts/2/orders?order_side=sell&page=0
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

위는 범위 밖 페이지 예시다. 전체 개수는 선택 계좌의 AI/EXECUTED/매수·매도 필터에 맞는 **주문 수**다.
주문별 마지막 체결시각 내림차순, 동률이면 주문 ID 내림차순이다. 하나의 주문이 여러 번 체결되어도 카드/페이지 항목은 한 건이다.
빈 결과는 orders=[], total_elements=0, total_pages=0, has_next=false다. 범위 밖 page는 전체 개수를 유지하면서 빈 배열을 반환한다.
JPA offset은 int 범위만 지원하므로 실제 데이터 범위 안이면서 offset이 int를 넘는 요청은 400이다.
새 체결이 추가되면 이후 페이지의 위치가 이동할 수 있다. 페이지 간 고정 스냅샷을 보장하지 않는다.

## 데이터와 집계

| 필드 | 출처 및 처리 |
|---|---|
| 주문 필드 | Order 저장값. order_status=executed, can_cancel=false |
| stock_name | KiwoomMarketClient.stockDetails. 반환 대상의 같은 종목은 요청당 한 번 조회 |
| executions | 해당 주문의 모든 Execution, 체결시각/체결 ID 오름차순 |
| 요약 quantity | 모든 체결 수량 합계 |
| 요약 total_amount | 모든 체결 가격 × 수량 합계 |
| 요약 average_price | total_amount / quantity, 소수 4자리 HALF_UP |
| 요약 executed_at | 주문의 마지막 체결 시각, +09:00 |
| 매수 요약 손익·수익률 | null |
| 단일 매도 요약 손익·수익률 | 기존 저장값 유지 |
| 복수 매도 요약 손익 | 모든 체결의 실현손익 합계. 하나라도 누락되면 null |
| 복수 매도 요약 수익률 | 합산 손익 / (합산 금액 - 합산 손익) × 100, 소수 4자리 HALF_UP. 손익 누락 또는 원가가 양수가 아니면 null |
| reason.summary | 기존 더미 유지: [더미] AI 판단에 따라 매수했어요. / 매도했어요. |

복수 매도의 원가 역산은 기존 AI 리포트 계산과 같다. 저장 손익의 소수 2자리 정밀도에 한정된다.
기존 Google Sheets API 문서에는 새 쿼리와 pagination이 아직 반영되지 않았으며 이 문서는 현재 구현의 연동 계약이다.

## 구현 흐름과 오류 처리

1. OrderController가 limit/page/order_side를 받아 서비스에 전달한다.
2. 서비스에서 입력을 검증한 후 읽기 전용 트랜잭션 안에서 계좌 소유권·활성 상태를 확인한다.
3. 페이지 요청은 같은 필터 조건으로 전체 주문 수를 조회한다. offset이 전체 범위 밖이면 빈 배열로 끝낸다.
4. OrderRepository.findHistory가 LEFT JOIN / GROUP BY로 주문별 마지막 체결 시각을 정렬하고 DB에서 페이지를 제한한다. countHistory는 주문만 세므로 복수 체결로 개수가 늘어나지 않는다.
5. 선택된 주문 ID의 체결을 ExecutionRepository.findForOrders로 일괄 조회하고 DTO로 복사한다. 체결이 없는 EXECUTED 주문은 500 INVALID_ORDER_DATA다. 페이지 요청은 해당 페이지의 주문만 검증하며, page 없는 요청은 기존처럼 모든 필터 후보를 검증한 뒤 limit을 적용한다.
6. 트랜잭션 밖에서 반환할 종목명만 조회한다. 외부 실패는 503 ORDER_DATA_UNAVAILABLE이며 일부 성공 결과로 숨기지 않는다.
7. page 요청에만 pagination을 붙여 반환한다.

토큰·근거 원문·외부 응답 원문은 로그에 기록하지 않는다.

## FE 변경 안내

- 전체 보기 진입: page=0으로 호출하고 orders 표시.
- 전체/매수/매도 탭 변경: page=0으로 초기화하고 order_side를 바꿔 재호출. FE가 현재 페이지 배열에서 다시 필터링하지 않는다.
- 페이지 이동: 선택한 order_side와 limit을 유지하고 page만 변경한다.
- total_pages로 번호 버튼, has_next로 다음 버튼을 제어한다. 화면 페이지 번호는 API page + 1이다.
- 빠르게 탭/페이지를 전환할 때 이전 요청 응답이 새 목록을 덮어쓰지 않도록 취소하거나 최신 요청만 반영한다.
- 홈의 기존 limit=3 요청은 유지할 수 있다. 판단 근거 보기는 기존 order_id를 그대로 사용한다.

## 검증

OrderHistoryTests는 기존 JSON 구조/홈 limit/정렬/인증/접근권한/외부 실패와 페이지 구간, 필터별 개수, 복수 체결, 평균가격 정밀도, 누락 손익, 빈 페이지, 잘못된 입력을 검증한다.

```powershell
.\gradlew.bat test --tests '*OrderHistoryTests' --no-daemon
.\gradlew.bat test spotbugsMain --no-daemon
```

자동 검증은 H2와 모의 키움 응답을 사용한다. 실제 FE 브라우저, 운영 MySQL, 실제 키움 호출은 검증 범위에 포함하지 않는다.
