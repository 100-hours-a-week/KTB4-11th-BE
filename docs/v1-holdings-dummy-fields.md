# v1 보유 종목 조회와 대체값

- API: GET /api/v1/users/me/accounts/{account_id}/holdings
- 홈: sort=latest_purchase&order=desc&limit=3. 전체 화면: limit 생략.
- 응답은 시트의 sector/total_cost를 유지한다. FE의 industry_name/total_cost_basis와 다르므로 FE 계약을 맞춰야 한다.
- 종목명/업종명: ka10100 name/upName. 현재가: ka10001 cur_prc. lastPrice는 전일종가이므로 현재가로 사용하지 않는다.
- 평가값은 요청 시점 REST 시세로 계산한다. 자동 재조회나 웹소켓 연결은 없다. 최신 매수순은 선택 종목만 조회하고, 평가순은 전체 후보의 시세를 조회한 뒤 제한한다.

| 필드 | 조건 | 반환값 | 근거·교체 방법 |
|---|---|---|---|
| sector | 키움 upName이 없거나 공백 | 문자열 "미분류" | 사용자 승인 표시 대체값. 공식 예시 값이라고 주장하지 않는다. 키움이 업종을 제공하면 실제 값 반환 |

고정 종목명·가짜 시세·가짜 수량·가짜 평가값은 사용하지 않는다. 필수 외부 데이터 실패는 503 HOLDINGS_DATA_UNAVAILABLE다.
평균단가/평가손익은 2자리, 수익률은 4자리 HALF_UP. 정렬은 반올림 전 수익률을 사용한다. 매도 예약 수량은 체결 전까지 포함한다.
최근 매수 정렬은 BUY 체결시각 기준, 매수 이력 누락은 마지막, 동률은 종목코드 오름차순이다. 빈 계좌는 200과 빈 배열. 잘못된 sort/order/limit는 400 INVALID_HOLDINGS_QUERY.

## 구현 한계와 진단

- 운영 키움 조회 제한을 고려해 동일 KiwoomMarketClient의 조회 TR은 직렬로 수행하며 응답 완료 후 다음 조회까지 최소 220ms를 둔다. 배치 API는 추가하지 않았다. N개 시세와 K개 반환 종목 정보에 N+K번의 조회가 필요하다. 많은 종목·동시 요청에서는 지연이 늘어나므로 배치 조회로 개선한다.
- 기존 설정은 운영 도메인이다. 모의투자 전환 시 TR당 1초 제한에 맞춰 조절해야 한다. 여러 서버에서 같은 토큰을 공유할 때도 별도 분산 제한이 필요하다.
- DEBUG: 요청 파라미터/DB 종목 수/종목별 평가 단계. INFO: 완료 개수/소요시간. WARN: 접근 실패/업종 대체. ERROR: 실패 단계/종목코드/안전한 공급자 코드 또는 예외 종류.
- 로그에 JWT·접근토큰·외부 응답 원문·잔액·취득원가를 출력하지 않는다.
- 실제 인증 호출 검증은 수행하지 않았다. 자동 테스트는 외부 응답을 대체한다.

## 공식 근거

- [키움 공식 명세](https://raw.githubusercontent.com/Kiwoom-Securities/Kiwoom-REST-API/main/kiwoom/_data/kiwoom_api_spec.json)
- [키움 호출 제한](https://openapi.kiwoom.com/intro?dummyVal=0)
