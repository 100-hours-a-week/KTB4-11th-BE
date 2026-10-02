# 지정가 주문 생성 API (v1)

`POST /api/v1/accounts/{accountId}/orders`는 키움에 실제 주문을 보내지 않고 BE의 모의 주문을 생성한다.
구현 순서, 정책 결정 과정과 시장 검증의 자세한 설명은 [주문 생성 구현 흐름](stockspoon_order_create_flow.md)에 기록했다.
종목별 WebSocket 구독, 장 마감 취소와 재기동 복구는 [주문 종목 WebSocket 생명주기](stockspoon_order_websocket_lifecycle.md)에 기록했다.
v1에서는 AI가 사용자 계좌를 대신 운용한다. 요청 인증은 기존 access JWT 쿠키와 CSRF 토큰을 사용하며,
AI가 서명한 JWT에는 `actor: "AI"`가 있어야 한다. JWT `sub`의 사용자와 주문 계좌 소유자가
일치해야 한다. 일반 사용자 로그인 JWT는 `actor`가 없으므로 주문 생성에서 403으로 거절한다.

## 요청

```json
{
  "stock_code": "005930",
  "stock_name": "삼성전자",
  "order_side": "buy",
  "order_type": "limit",
  "limit_price": 70000,
  "quantity": 2,
  "reason": "목표 가격에 접근해 매수를 결정했어요.",
  "reasoning": [{"label": "가격", "body": "목표 가격에 접근했어요."}]
}
```

`reason`은 필수 문자열이며 공백만 있는 값은 거절한다. 최대 100,000자(Java UTF-16 길이)이며,
기존 `decision_id`·`summary` 객체는 더 이상 받지 않는다. `ai_order_reports.reason`에 주문과
같은 트랜잭션으로 저장한다. `order_id` UNIQUE 외래키로 주문당 최대 한 리포트를 보장한다.
상세 조회와 DB 이관은 [AI 리포트 상세 조회](stockspoon_ai_report.md)를 참고한다.
`order_source`는 body에서 받지 않고 v1 주문을 `AI`로 저장한다.

현재 주문 생성은 `ka10100`의 시장구분코드 `0`(코스피)과 `8`(국내 ETF)만 허용한다.
`60/70/90`인 ETN과 `10`인 코스닥은 거절한다. `marketCode=0`만으로 보통주·우선주만을
정확히 가려내는지는 검증되지 않았으므로, 이는 상품 유형을 엄격히 제한하는 검사와 다르다.

이번 단계는 지정가만 접수한다. 시장가는 체결 엔진과 함께 구현한다. 동일 요청의 재전송도
별도 주문으로 접수한다. 요청 재전송의 중복 방지는 현재 제공하지 않는다.

## 접수 검증

1. 서버 현재 시각을 `Asia/Seoul`로 변환해 **09:00:00 이상, 15:30:00 미만**이면
   주문 생성 가능 시간으로 본다. 요일·휴일은 검사하지 않는다. 키움 `0s` 장 상태는
   주문 접수 조건에 사용하지 않으며, 이 시간 규칙은 실제 장 개장을 보장하지 않는다.
   원래 v1 정책 문서의 실제 거래일·정규장 검사 조건은 이후 합의에 따라 현재 단계에서 적용하지 않는다.
2. 키움 종목정보 조회 `ka10100`으로 종목 코드와 시장구분을 확인한다. 조회 실패·불완전한
   응답은 주문을 거절한다. 시장구분코드 `0`(코스피)과 `8`(국내 ETF)만 주문 대상으로 취급한다.
   ETN 시장코드 `60/70/90`은 제외한다. **이후 합의한 현재 주문 접수 정책에 따라 개별 종목의 거래정지 여부는 검사하지 않는다.**
   `state`(증거금·담보·신용 등의 종목상태)와 `orderWarning`(투자유의 분류)은 주문 판정에
   사용하지 않는다. 이 응답으로 종목의 실제 거래 가능 여부를 보장하지 않는다.
3. 지정가가 한국거래소 호가가격단위에 맞는지 확인한다. ETF는 2,000원 미만 1원,
   이상 5원이다. 일반 KOSPI 주식은 가격대별 1·5·10·50·100·500·1,000원 단위다.
4. 주문 종목의 WebSocket REG 성공을 최대 10초 기다린다. 이미 등록된 종목은 구독을 공유한다.
   실패하거나 시간 안에 성공 응답을 받지 못하면 `503 MARKET_STREAM_UNAVAILABLE`로 거절하며
   주문과 예약금을 저장하지 않는다.
5. 계좌를 잠그고 소유자·활성 상태, 주문 가능 현금 또는 매도 가능 수량을 다시 확인한 뒤
   `pending` 주문을 저장한다. 실패한 요청은 주문 행을 남기지 않는다.

`ka10100`으로 종목을 검증한 뒤 주문 종목을 동적으로 구독한다. 구독은 해당 종목의 대기 주문이
남아 있는 동안 유지한다. 여러 계좌가 같은 종목을 주문해도 구독은 공유한다.
모든 종목의 대기 주문이 끝나면 웹소켓 연결도 종료한다. 다음 주문은 재연결과 REG가 성공해야 접수된다.

## 성공 응답

HTTP `201 Created`:

```json
{
  "message": "success",
  "order_id": 1001,
  "account_id": 11,
  "stock_code": "005930",
  "stock_name": "삼성전자",
  "order_side": "buy",
  "order_type": "limit",
  "limit_price": 70000,
  "quantity": 2,
  "order_status": "pending",
  "reserved_cash": 140000,
  "created_at": "2026-09-28T01:00:00Z",
  "executions": []
}
```

`created_at`은 UTC `Z` 형식으로 반환한다. 예를 들어 `2026-09-03T05:20:00Z`와
한국 시각 `2026-09-03T14:20:00+09:00`은 같은 순간이다. FE는 표시할 때 한국 시각으로
변환한다. 공유 API 문서의 주문 생성 응답에 있는 `+09:00` 예시도 실제 응답에 맞춰 UTC `Z`로
수정해야 한다.
주문 거절 시 `code`·`message`를 반환한다. 미인증 요청은 401, 타인 계좌는 403,
주문 가능 시간 밖은 `409 ORDER_WINDOW_CLOSED`, 키움 종목정보 확인 실패는 503이다.

## 연동 확인 사항

AI 팀은 BE와 같은 시크릿·발급자(`iss`)로 `type: "access"`, 사용자 ID `sub`, 만료 시각,
`actor: "AI"`를 담은 JWT를 생성한다. BE는 서명·발급자·만료·토큰 종류를 검증하고 주문 API에서
AI 출처를 확인한다. JWT 공유 시크릿은 AI 서버가 모든 사용자에 대한 AI 토큰을 만들 수 있는
권한이므로, 사용자에게 노출하거나 클라이언트 코드에 포함하면 안 된다.

키움 `ka10100`의 시장구분코드는 공식 명세를 기준으로 했다. 삼성전자(보통주)와
KODEX 200(ETF)은 실제 조회로 확인했으며, 우선주의 시장구분 응답은
추가 검증해야 한다. 시간 제한만으로 휴장일·임시 개장 지연·조기 마감을 반영할 수 없으므로
주문 가능 시간은 실제 거래 가능 상태와 다를 수 있다.

근거: [키움 공식 API 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json),
[한국거래소 주식 호가가격단위](https://regulation.krx.co.kr/contents/RGL/03/03010100/RGL03010100T3.jsp),
[한국거래소 ETF 호가가격단위](https://regulation.krx.co.kr/contents/RGL/03/03060101/RGL03060101.jsp).


## AI 종목명·판단 흐름 계약

주문 생성은 stock_name을 필수 문자열로 받는다(공백 불가, 최대 255 Java UTF-16 코드 단위).
AI가 제공한 원문을 ai_order_reports.stock_name에 저장하고, 주문 생성 응답과 리포트 상세 조회의
stock_name으로 그대로 반환한다. 이 이름을 반환하기 위해 외부 종목정보를 조회하지 않는다.
기존 리포트의 이름은 SQL로 추정해 채우지 않으며, 이름이 NULL이면 기존 상세 응답의
"[더미] 종목명"을 유지한다.

reason은 기존처럼 필수이며 상세 조회에서는 summary가 된다.
reasoning은 선택 항목인 순서 있는 [{label, body}] 배열이다. label은 공백 불가·최대 255자,
body는 공백 불가·최대 16,000자(Java UTF-16 기준)이고 null 항목은 거절한다.
생략·null·빈 목록은 빈 배열로 저장/반환한다. API 필드명은 thoughts가 아니라 reasoning이다.
매수·매도, 지정가·시장가 모두 같은 리포트 계약을 사용한다.

reasoning은 ai_report_reasoning 테이블에 report_id와 reasoning_index로 순서를 보존하여
주문·리포트와 같은 트랜잭션으로 저장한다. 상세 조회에는 reasoning을 그대로 반환한다.
focused_news·decision_summary를 reason에 JSON 문자열로 저장하는 계약은 추가하지 않았다.

기존 DB에는 새 앱 배포 전에 db/ai_report_reasoning.sql을 1회 적용해야 한다.
이 SQL은 자동 실행되지 않으며 운영 DB 적용은 별도 배포 작업이다.
