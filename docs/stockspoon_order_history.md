# 체결 완료 AI 주문 내역 조회 (v1)

## 요청과 응답 계약

- `GET /api/v1/accounts/{account_id}/orders`, 기존 로그인 쿠키 인증.
- 선택 쿼리 `limit`: 생략하면 전체, 양의 정수이면 최대 해당 개수. 보유 종목 조회의 limit와 같은 입력 규칙이다. 0, 음수, 소수, 문자열, 빈 값, int 범위 초과는 400 `INVALID_ORDER_QUERY`.
- 본인 소유 활성 계좌만 조회한다. 존재하지 않거나 다른 사용자 소유이거나 비활성 계좌이면 404 `ACCOUNT_NOT_FOUND`.
- v1에서는 AI 주문 중 체결 완료 주문만 반환한다. 매수/매도 필터링은 프론트에서 수행한다.
- 체결시각 내림차순, 같은 시각이면 주문 ID 내림차순. 정렬 후 limit를 적용한다.
- 결과가 없으면 `{"account_id":123,"orders":[]}`.

응답 필드명과 구조의 기준은 [API 문서의 주문 내역 조회](https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152#gid=2138787152)이다.

```json
{
  "account_id": 123,
  "orders": [{
    "order_id": 1002,
    "stock_code": "005930",
    "stock_name": "삼성전자",
    "order_source": "AI",
    "order_side": "buy",
    "order_type": "limit",
    "order_status": "executed",
    "quantity": 2,
    "limit_price": 70000,
    "reserved_cash": 0,
    "created_at": "2026-09-01T09:00:00+09:00",
    "canceled_at": null,
    "reason": {"summary": "[더미] AI 판단에 따라 매수했어요."},
    "executions": [{
      "execution_id": 2001,
      "execution_price": 69500,
      "execution_quantity": 2,
      "realized_pnl": null,
      "realized_return_percent": null,
      "created_at": "2026-09-01T09:01:00+09:00"
    }],
    "execution_summary": {
      "quantity": 2,
      "average_price": 69500,
      "total_amount": 139000,
      "executed_at": "2026-09-01T09:01:00+09:00",
      "realized_pnl": null,
      "realized_return_percent": null
    },
    "can_cancel": false
  }]
}
```

## 데이터 근거

|필드|출처와 처리|
|---|---|
|주문 ID·종목 코드·매수/매도·유형·수량·지정가·예약금·생성시각|기존 Order 저장값|
|stock_name|기존 KiwoomMarketClient.stockDetails. 반환 대상의 동일 종목은 요청당 한 번 조회|
|order_status|체결 완료 주문만 조회하므로 executed. 목록 API 문서 필드 유지|
|canceled_at|Order.cancelledAt를 문서 표기 canceled_at로 직렬화. 체결 주문에서는 null|
|can_cancel|체결 완료 주문이므로 false|
|executions|기존 Execution 저장값. v1은 주문당 1건이지만 문서의 배열 구조 유지|
|execution_summary|단일 체결 수량·가격·시각·손익. 총액은 체결가격 × 체결수량|
|실현손익·수익률|저장값 그대로. 매수는 null, 매도는 저장된 소수 정밀도를 유지|
|시간|저장 Instant를 +09:00 OffsetDateTime으로 반환|
|reason.summary|승인된 더미. 매수는 `[더미] AI 판단에 따라 매수했어요.`, 매도는 `[더미] AI 판단에 따라 매도했어요.`|

리포트 상세 응답의 필드 제거 결정과 목록 API 계약은 별개다. 목록에서는 API 문서의 order_status를 유지하며, report_id와 execution_count는 추가하지 않는다.

## 구현 단계와 이유

1. 승인된 dev 병합으로 기존 보유 종목 조회와 stockDetails 구현을 확보했다. 같은 외부 연동을 재사용하기 위한 단계이며 신규 클라이언트나 의존성을 만들지 않았다.
2. 실제 로그인·DB·MVC를 사용하는 OrderHistoryTests를 먼저 작성했다. GET이 없을 때 6개 모두 실패하여 새 동작을 검증하는 테스트임을 확인했다.
3. 기존 주문 컨트롤러에 GET을 추가하고 전용 응답 DTO로 문서의 필드명, 배열, null, 중첩 객체를 명시했다. 엔티티 직접 직렬화를 피한다.
4. 기존 계좌 소유권 조회로 접근을 확인한 뒤 EXECUTED 주문 중 AI 주문을 선택한다. 다른 계좌·대기·취소 주문은 대상에서 제외한다.
5. 대상 주문의 체결을 IN 쿼리로 한 번에 읽는다. 부분 체결 미지원 정책에 따라 모든 대상 주문의 체결이 정확히 1건인지 검증한다. 0건 또는 여러 건이면 limit 적용 전 요청 전체를 500 INVALID_ORDER_DATA로 실패시킨다.
6. DB 트랜잭션 안에서 응답에 필요한 값만 복사한다. 트랜잭션 종료 후 체결시각/ID 순으로 정렬하여 limit를 적용한다. 외부 요청 중 엔티티 지연 로딩이나 DB 트랜잭션 유지를 피하기 위해 기존 보유조회 구조를 따른다.
7. 선택된 종목명만 외부에서 조회한다. 외부 조회 실패는 더미로 숨기지 않고 503 ORDER_DATA_UNAVAILABLE로 반환한다.
8. 시작·DB 후보 수는 debug, 잘못된 입력·접근은 warn, 데이터 오류·외부 실패는 error, 응답 개수·소요시간은 info로 기록한다. 토큰, 근거 원문, 금액, 외부 응답 원문은 로그에 넣지 않는다.

## 검증과 개선 사항

OrderHistoryTests 6개가 정렬과 limit, 타 상태/계좌 제외, 정확한 JSON 필드 집합, 손익 정밀도와 null, 잘못된 limit, 인증과 접근권한, 0/복수 체결, 외부 실패를 검증한다. 실행은 `./gradlew test --tests '*OrderHistoryTests'` 및 전체 `./gradlew build`.

- 더미인 필드는 reason.summary 하나다. AI의 긴 reason 원문을 임의로 축약하지 않는다. 요약 수신/생성 정책이 확정되면 별도 요약 데이터로 대체한다. 주문과 1:1 리포트 및 상세 조회 원문은 변경하지 않았다.
- 현재 전체 후보를 읽고 검증·정렬한다. 이력이 커지면 DB 체결시각 정렬과 페이지 조회가 필요하다. 변경 시 전체 후보 오류 처리 정책과도 함께 합의해야 한다.
- 종목명은 요청 시점 외부 정보다. 주문 시점 종목명 보존이 필요하면 스냅샷 저장 정책을 별도로 확정해야 한다.
- 검증은 H2와 모의 키움 응답을 이용한다. 실제 키움 연결과 운영 MySQL을 사용한 검증은 포함하지 않는다.

검증 결과 (2026-10-01): 신규 통합 테스트 6개 통과, 전체 Gradle build 및 SpotBugs 통과. 별도 코드 리뷰에서 구체적 정확성·보안 결함은 발견되지 않았다. 테스트 클래스가 트랜잭션을 사용하므로 외부 호출의 트랜잭션 경계는 테스트로 입증하지 못하며 구현 구조를 확인했다.

## 요청 처리 순서: 코드를 따라 읽는 방법

진입 경로: 인증 처리 → OrderController.list → OrderHistoryService.list → 응답 DTO 직렬화.

1. 기존 Spring Security가 로그인 쿠키를 검증한다. 인증되지 않으면 401로 종료한다.
2. OrderController.list가 JWT subject를 사용자 ID로 변환한다. 숫자로 변환할 수 없으면 401 INVALID_TOKEN으로 종료한다. accountId와 선택 limit를 서비스로 전달한다.
3. OrderHistoryService.list의 parseLimit가 입력을 검사한다. 생략하면 Integer.MAX_VALUE, 유효한 양의 정수면 그 수를 사용한다. 실패하면 warn 로그와 400 INVALID_ORDER_QUERY로 종료한다.
4. read.execute가 읽기 전용 DB 트랜잭션을 연다. AccountRepository.findByIdAndUserIdAndActiveTrue로 본인 소유 활성 계좌를 확인한다. 실패하면 warn 로그와 404 ACCOUNT_NOT_FOUND로 종료한다.
5. OrderRepository.findAllByAccountIdAndStatus로 EXECUTED 주문을 읽고 Source.AI만 남긴다. 후보가 없으면 빈 목록으로 트랜잭션을 마친다.
6. ExecutionRepository.findForOrders가 후보 주문 ID 전체의 체결을 한 번에 조회한다. 주문 ID별로 묶은 뒤 각 주문의 체결 수가 정확히 1건인지 확인한다. 0건이나 여러 건이면 error 로그와 500 INVALID_ORDER_DATA로 종료한다. 아직 limit는 적용하지 않는다.
7. snapshot이 Order와 Execution의 저장값을 Item, ExecutionItem, ExecutionSummary로 복사한다. 단일 체결이므로 평균가격은 체결가격, 총액은 체결가격 × 체결수량이다. 손익은 저장값을 사용하고 시간은 +09:00으로 변환한다. reason.summary에 승인된 매수/매도 더미를 넣는다. AI 리포트 원문은 조회하거나 수정하지 않는다.
8. read.execute가 종료된 후 DB 후보 수를 debug로 기록한다. 체결시각 내림차순, 동률이면 orderId 내림차순으로 정렬하고 limit를 적용한다.
9. 반환할 주문의 stockCode별로 KiwoomMarketClient.stockDetails를 호출한다. 요청 안에서 HashMap에 종목명을 보관하여 같은 종목을 한 번만 호출한다. 실패하면 예외 종류만 error로 기록하고 503 ORDER_DATA_UNAVAILABLE로 종료한다.
10. withStockName으로 종목명이 채워진 Item을 만든다. 완료 개수와 소요시간을 info로 기록하고 OrderHistoryResponse를 반환한다. Spring이 DTO의 JsonProperty에 따라 account_id/orders와 문서의 하위 필드들을 JSON으로 직렬화한다.

읽을 파일: src/main/java/com/stock_spoon/river_be/order/OrderController.java, OrderHistoryService.java, OrderHistoryResponse.java, OrderRepository.java, ExecutionRepository.java. 검증 파일: src/test/java/com/stock_spoon/river_be/order/OrderHistoryTests.java.

## 대표 시나리오

|상황|처리 경로와 결과|
|---|---|
|최근 주문 3개 조회|limit=3 검증 → 계좌 확인 → 모든 체결 완료 AI 주문 검증 → 체결시각/ID 정렬 → 상위 3개 종목명 조회 → 200|
|limit 없이 조회|같은 절차로 전체 체결 완료 AI 주문 반환|
|같은 종목의 매수·매도 주문이 여러 개|각 주문은 별도 카드 데이터. 종목명 외부 호출은 요청당 한 번. 매도 손익은 저장값, 매수 손익은 null|
|체결 완료 주문이 없음|계좌 확인 후 orders=[]로 200. 외부 종목 조회 없음|
|대기·취소 주문이 함께 있음|대기·취소 주문은 후보에서 제외. 반환하지 않음|
|다른 계좌 또는 비활성 계좌|소유권/활성 검사에서 404. 주문·외부 종목 조회 진행하지 않음|
|잘못된 limit|계좌 조회 전에 400 INVALID_ORDER_QUERY|
|체결 완료 주문인데 체결이 0건/복수 건|limit와 관계없이 해당 계좌의 후보 검증에서 전체 요청을 500 INVALID_ORDER_DATA로 종료|
|반환 대상 종목의 외부 정보 조회 실패|DB 읽기는 종료된 상태. 전체 요청을 503 ORDER_DATA_UNAVAILABLE로 종료|
|사용자가 매수/매도 탭 선택|서버는 양쪽을 반환. 프론트가 order_side로 필터링|
