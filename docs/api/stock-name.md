# 종목명 조회

GET /api/v2/stocks/{stock_code}

일반 사용자 access_token 쿠키 인증이 필요하다. 계좌 및 보유 여부와 관계없이 호출한다.
종목코드는 숫자·대문자 6자리 문자열이며 선행 0을 유지한다.

성공 예시:

```json
{"stock_code":"005930","stock_name":"삼성전자"}
```

오류 응답은 code, message 두 필드다.

| 상태 | code | 의미 |
|---|---|---|
| 400 | INVALID_STOCK_CODE | 종목코드 형식 오류 |
| 401 | UNAUTHORIZED | 인증 없음 또는 실패 |
| 403 | FORBIDDEN | 사용자 API 접근 불가 주체 |
| 503 | STOCK_INFO_UNAVAILABLE | 종목정보 조회 실패 |

기존 KiwoomMarketClient.stockDetails 및 7일 종목정보 캐시를 사용한다.
시세와 보유정보는 반환하지 않는다. 종목명은 공급자 원문을 유지한다.
현재 클라이언트는 미등록 종목과 공급자 장애를 명확히 구분하지 못하므로 404를 보장하지 않는다.
FE는 반환된 stock_name을 상세페이지 제목으로 사용한다.
