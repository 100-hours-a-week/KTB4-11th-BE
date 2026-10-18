# v2 관심 API

사용자 단위이며 계좌 ID를 받지 않는다. 기존 access_token 쿠키와 CSRF 정책을 사용한다.

| 요청 | 응답 |
|---|---|
| GET /api/v2/users/me/watchlist | 200, message: success 및 watchlists 배열 |
| POST /api/v2/users/me/watchlist, 본문 {"stock_code":"005930"} | 신규 201 / 중복 200, {"message":"success","stock_code":"005930"} |
| DELETE /api/v2/users/me/watchlist/005930 | 등록 유무와 관계없이 200, {"message":"delete_success"} |

목록 항목: stock_code, stock_name, sector(null 가능). 빈 목록은 []이며 최근 등록순, 동률은 내부 종목 ID 내림차순이다.
시세와 랭킹은 FE가 시세 서버에서 직접 조회한다. 본 API는 시세를 호출하지 않는다.

오류: 요청 본문 검증은 기존 400 INVALID_REQUEST, 삭제 코드 형식 오류는 400 INVALID_STOCK_CODE,
없는 종목 등록은 404 STOCK_NOT_FOUND, 여러 시장에 같은 코드가 있으면 409 AMBIGUOUS_STOCK_CODE.
인증/CSRF는 기존 401/403 규칙을 따른다.

## DB 준비

- db/watchlist.sql은 수동 적용용이며 자동 실행하지 않는다. 기존 운영 테이블과 대조한 후 적용한다.
- stocks가 이미 존재하고 sector 컬럼이 없으면 SQL 주석의 ALTER가 필요하다.
- 종목 마스터에는 실제 종목코드·종목명·시장·업종을 별도로 적재해야 한다. 샘플 데이터를 운영에 자동 삽입하지 않는다.
- 종목 마스터가 비어 있으면 등록은 404다. 종목 마스터 수집·갱신 경로는 이 변경에 포함하지 않았다.
- 사용자 행 잠금으로 동일 사용자의 등록·해제를 직렬화하고 복합 PK로 중복을 방지한다.
- FE는 두 탭의 관심 상태를 공유하고 실패 시 상태를 유지하며 로그아웃 시 사용자 캐시를 비운다.