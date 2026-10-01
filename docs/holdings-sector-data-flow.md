# 보유 종목 조회: sector 데이터 흐름과 검증 근거

확인일: 2026-10-01. 대상: `feat/account-holdings`의 현재 구현.

이 문서는 계좌의 보유 종목을 조회할 때 종목명과 업종명이 어디서 들어오고, 어떤 검증을 거쳐 API 응답으로 나가는지 설명한다. **공식 명세 확인과 대체 응답을 사용한 자동 테스트까지 수행했으며, 실제 인증을 사용한 키움 호출은 아직 검증하지 않았다.**

## 1. 근거의 종류

| 구분 | 확인한 사실 | 근거 |
|---|---|---|
| 공식 명세 | ka10100은 종목코드를 받아 종목정보를 조회한다. 응답에 code, name, upName이 있다 | [키움 공식 저장소의 ka10100 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json#L27786) |
| 공식 필드 의미 | upName은 업종명이고 lastPrice는 전일종가다 | [공식 응답 필드 정의](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json#L27944) |
| 서비스 정책 | sector는 upName을 사용하며 누락·공백은 미분류로 반환한다 | 사용자의 정책 질문 답변: “제안대로 확정” 및 [대체값 기록](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/docs/v1-holdings-dummy-fields.md>) |
| 구현 | 실제 호출·검증·변환 코드 | 아래 단계별 코드 링크 |
| 자동 검증 | 요청 형식, 업종 연결, 응답 필드, 실패 처리 | 아래 테스트 링크 |

공식 저장소 링크는 main을 가리키므로 이후 명세가 바뀔 수 있다. 업종명으로 FE의 자체 카테고리를 추정하지 않는다. 공식 예시의 특정 종목·업종 조합도 실제 분류를 보증하는 자료로 사용하지 않는다.

## 2. 전체 흐름

```text
FE GET /holdings?sort=latest_purchase&order=desc&limit=3
  → 인증 사용자와 계좌 소유권 확인
  → DB에서 종목코드·수량·총 취득원가·필요한 매수 체결시각 읽기
  → 정렬 기준에 맞춰 후보 선택 및 현재가 조회·평가
  → 반환할 종목마다 stockDetails(종목코드)
  → 키움 토큰 확보 → ka10100 POST 요청
  → 성공 코드·종목코드·종목명·업종명 타입 검증
  → upName 공백 제거 → 공백이면 미분류
  → DTO의 sector에 넣기 → 200 JSON 응답
```

## 3. 단계별 동작

### 단계 1: 계좌의 보유 종목코드 확보

서비스는 로그인 사용자가 소유한 활성 계좌인지 먼저 확인한다. 확인된 계좌의 Holding에서 종목코드, 보유수량, 총 취득원가를 읽는다. 외부 종목정보는 **DB에서 확보한 종목코드**로 연결한다. 종목명으로 검색하거나 이름을 유추하지 않는다.

DB 값은 읽기 트랜잭션 안에서 복사하고, 키움 요청은 트랜잭션 종료 후 수행한다. 다른 사용자 계좌·비활성 계좌·없는 계좌는 404 ACCOUNT_NOT_FOUND이며 외부 시세를 조회하지 않는다.

근거: [AccountHoldingsService.java](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:55>).

### 단계 2: 정렬·limit에 따라 조회 대상 결정

최근 매수순은 DB의 마지막 BUY 체결시각으로 정렬한 뒤 limit를 적용하므로 선택 종목만 현재가를 조회한다. 평가금액순·수익률순은 모든 후보의 현재가로 평가하고 정렬한 뒤 limit를 적용한다. 업종명·종목명 요청은 최종 반환 대상에만 수행한다.

홈에서 limit=3을 전달해도 같은 API를 사용한다. 전체 화면은 limit를 생략한다. 최신 매수순이 기본이고 기본 방향은 desc다. 동률은 종목코드 오름차순이며 매수 체결 이력 누락은 마지막이다.

근거: [후보 선택 및 평가](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:74>), [최종 대상의 종목정보 조회](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:107>).

### 단계 3: 키움 종목정보 요청

stockDetails는 공통 stockInfoResponse를 호출한다. 종목코드가 6자리 숫자·대문자인지 검사하고 KiwoomTokenProvider에서 토큰을 확보한다. 아래는 요청 구조 예시이며 실제 인증 호출 결과가 아니다.

```http
POST https://api.kiwoom.com/api/dostk/stkinfo
api-id: ka10100
Authorization: Bearer <접근토큰>
Content-Type: application/json

{"stk_cd":"005930"}
```

공식 명세의 POST 경로, TR ID, Bearer 헤더, stk_cd를 구현에 연결했다. 동일 클라이언트의 조회는 직렬로 실행하고 응답 완료 후 다음 조회까지 최소 220ms를 둔다. 이 간격은 현재 구현의 제한 방식이며 여러 서버 사이의 호출을 조정하지는 않는다.

근거: [공식 요청 정의](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json#L27786), [토큰 확보와 TR 선택](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java:102>), [HTTP 요청 실행](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java:128>).

### 단계 4: 응답을 사용할 수 있는지 검증

공통 처리에서 응답 존재 여부와 return_code가 0인지 확인한다. 이어 stockDetails는 요청 종목코드와 응답 code가 같은지, name이 비어 있지 않은 문자열인지 검사한다. upName은 누락·null을 허용하되, 값이 있다면 문자열이어야 한다.

키움 명세에서 선택 필드여도 서비스가 종목명을 표시하려면 필요하므로 name을 필수로 검증한다. 업종이 없는 정상 응답과 데이터 타입이 깨진 응답은 다르게 처리한다.

근거: [성공 여부 검사](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java:113>), [종목코드·종목명·업종 타입 검사](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java:88>).

### 단계 5: upName을 sector로 변환

| 입력 | 처리 | API의 sector |
|---|---|---|
| 유효한 업종 문자열 | 앞뒤 공백 제거 | 정리한 업종명 |
| 필드 누락·null·빈 문자열·공백만 존재 | 승인된 표시 대체값 적용, WARN 기록 | 미분류 |
| 숫자·객체·배열 등 잘못된 타입 | 외부 데이터 실패 처리 | 503, 정상 holdings 응답 생성 중단 |

문자열의 업종 자체는 재분류하지 않는다. 업종코드 사전 조회나 종목코드와 업종코드의 추정 연결도 수행하지 않는다. ka10100의 업종명을 직접 사용할 수 있으므로 별도 업종 목록 연결이 현재 흐름에 필요하지 않다.

근거: [문자열 정리](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java:94>), [미분류 정책과 경고 로그](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:116>).

### 단계 6: 문서 계약에 맞춰 JSON 생성

| 데이터 | 응답 필드 | 연결 방식 |
|---|---|---|
| Holding의 종목코드 | stock_code | ka10100 응답 code와 일치 검증 후 사용 |
| ka10100 name | stock_name | 검증한 종목명 |
| ka10100 upName | sector | 공백 정리 및 승인 대체 정책 |
| Holding의 총 취득원가 | total_cost | 저장한 원가 사용 |
| ka10001 cur_prc | current_price | 별도 현재가 조회 |

실제 JSON 필드는 sector와 total_cost다. industry_name과 total_cost_basis를 별칭으로 추가하지 않는다. ka10100의 lastPrice는 전일종가이므로 현재가로 사용하지 않는다. 응답 최상위는 message, account_id, holdings이고 업종명은 각 holdings 항목의 sector에 들어간다.

근거: [응답 DTO](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/dto/AccountHoldingsResponse.java:9>), [DTO 생성](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:118>).

## 4. 실패 원인을 찾는 로그

| 위치·수준 | 기록 내용 | 확인할 문제 |
|---|---|---|
| 서비스 DEBUG | 조회 파라미터·DB 후보 수·종목별 평가 완료 | 대상 선택과 평가 진행 위치 |
| 클라이언트 DEBUG | ka10100 종목정보 수신·종목코드 | 해당 종목의 응답 수신 여부 |
| 서비스 WARN | 업종 대체·field=sector·종목코드 | upName 누락 또는 공백 |
| 클라이언트 ERROR | 통신 실패 또는 조회 거부·TR·종목코드·안전한 반환 코드 | 네트워크 오류·공급자 거절 |
| 서비스 ERROR | stage=current_price 또는 stock_details·종목코드·예외 종류 | 실패한 외부 조회 단계 |
| 서비스 INFO | 완료 개수·소요시간 | 정상 완료 여부와 지연 |

키움 요청·종목명 검증·업종 타입 검증이 실패하면 서비스는 503 HOLDINGS_DATA_UNAVAILABLE을 반환한다. 일부 종목만 성공했더라도 성공 배열로 반환하지 않는다. 현재 로그는 실패 단계까지 알려주지만 종목명 검증 실패와 업종 타입 실패는 모두 IllegalStateException으로 기록되므로 로그만으로 두 경우를 세분하지 못한다. 토큰·JWT·외부 응답 원문·취득원가는 로그에 출력하지 않는다.

근거: [외부 실패 변환과 로그](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/main/java/com/stock_spoon/river_be/account/service/AccountHoldingsService.java:144>).

## 5. 자동 테스트로 확인한 범위

클라이언트 테스트는 실제 키움 대신 MockRestServiceServer를 사용한다. 올바른 경로·POST·TR 헤더·Bearer 토큰·종목코드 요청과 name/upName 연결을 검사하며, 다른 종목코드·빈 종목명·실패 반환 코드를 거부하는지 확인한다. 서비스 테스트는 업종 대체, 외부 조회 실패, 정렬과 limit, HTTP 응답의 정확한 필드를 검증한다.

- [KiwoomMarketClientTests.java](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/test/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClientTests.java>)
- [AccountHoldingsTests.java](</C:/Users/syc71/Documents/ChatGPT/teamProject(Stock_Spoon)/river_be/src/test/java/com/stock_spoon/river_be/account/service/AccountHoldingsTests.java>)

재현 명령(저장소 루트, PowerShell):

```powershell
.\gradlew.bat test --tests '*KiwoomMarketClientTests' --tests '*AccountHoldingsTests'
```

기존 전체 검증 `test spotbugsMain`은 성공했다: 테스트 147개 중 142개 통과, 5개 건너뜀, 실패·오류 0개. 이는 대체 응답을 사용하는 검증 결과이며 실제 키움의 업종 정확성·인증·운영 응답을 보증하지 않는다.

## 6. FE 연동과 남아 있는 확인

FE 시안의 플랫폼·반도체 같은 칩이 키움 업종명과 동일하다는 근거는 아직 없다. v1은 승인된 정책대로 키움 업종명을 반환한다. 시안의 자체 카테고리가 필요하면 별도의 분류 기준·매핑·누락 처리를 확정해야 한다.

현재 API에는 업종 필터 쿼리가 없다. FE가 limit 없이 받은 전체 응답을 sector로 분류하는 것은 가능하다. 서버 필터가 필요하면 별도 계약을 정해야 한다. 전체 보기 버튼은 FE 정책대로 항상 표시한다.

현재가와 업종정보는 종목별 REST 요청에서 읽으므로 모든 종목이 정확히 같은 시각의 스냅샷은 아니다. 한 번의 FE 호출에서 처리하며 자동 재조회는 하지 않는다. 실제 인증 호출로 응답 필드와 빈 업종 사례를 확인하는 작업은 남아 있다.
