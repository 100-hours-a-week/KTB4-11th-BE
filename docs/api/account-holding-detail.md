# 단일 종목 보유 조회

GET /api/v1/accounts/{account_id}/holdings/{stock_code}

일반 사용자 access_token 쿠키 인증 필요. 본인의 활성 계좌만 조회한다.
종목코드는 숫자·영문 대문자 6자리 문자열이다.

```json
{"account_id":1,"stock_code":"005930","is_held":true,"quantity":10,"total_cost":2000000,"average_purchase_price":200000}
```

응답: 계좌 ID, 코드, 보유 여부, 수량, 현재 보유분 총 취득원가, 평균 매입단가.
평균 단가는 총 취득원가/보유수량을 소수2자리 HALF_UP으로 계산한다.
미보유/전량매도는 200, is_held=false와 수량·원가·평단가 0이다.
형식 오류 400 INVALID_STOCK_CODE, 접근 불가/없는/비활성 계좌 404 ACCOUNT_NOT_FOUND.
기존 보안 계층이 401 UNAUTHORIZED 또는 403 FORBIDDEN을 반환한다.
잘못 저장된 보유 데이터는 500 INVALID_HOLDINGS_DATA로 처리한다.
Redis·키움·시세서버는 호출하지 않는다. 종목 마스터 존재 여부는 조회하지 않으며, 유효한 형식의 미보유 코드는 false로 응답한다.
FE는 실시간 현재가로 평가금액=현재가*quantity, 평가손익=평가금액-total_cost, 수익률=평가손익/total_cost*100을 계산한다.
총원가가 0이거나 현재가 미수신이면 수익률 계산을 생략한다. 체결 후 보유정보를 재조회한다.
