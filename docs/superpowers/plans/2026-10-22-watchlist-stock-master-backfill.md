# 관심 종목 마스터 지연 적재 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관심 등록 시 stocks에 종목이 있으면 재사용하고, 없으면 기존 키움 인증으로 키움 서버를 직접 조회해 기본정보를 확보해 저장한 뒤 관심 등록한다(B안).

**Architecture:** WatchlistService는 사용자 확인과 관심 등록을 담당하고, 신규 StockCatalogService는 DB 우선 조회·외부 메타데이터 확보·종목 저장을 담당한다. 외부 호출은 트랜잭션과 사용자 행 잠금 밖에서 수행한다. 종목 저장과 관심 등록을 각각 짧은 트랜잭션으로 나누고, 동시 최초 등록은 DB 유일 제약과 새 트랜잭션의 재조회로 수렴시킨다.

**Tech Stack:** Java 21, Spring MVC/Data JPA, TransactionTemplate, 기존 KiwoomMarketClient, JUnit/MockMvc/H2.

**Spec:** 사용자가 선택한 B안: “있으면 DB에서 쓰고 없으면 키움에서 가져와 채우기.” 아래 정책과 호출 흐름을 구현 기준으로 사용한다.

## Global Constraints

- 작성 기준은 dev HEAD `9ba8078`. 실행 전 대상 브랜치·미커밋 변경·최신 호출부를 재확인한다. 이번 요청은 계획 작성이며 구현·외부 API 실행·DB 변경·커밋·푸시를 수행하지 않는다.
- 기존 GET/POST/DELETE `/api/v2/users/me/watchlist`, 응답 DTO, 사용자 단위 목록·정렬·중복 등록·삭제 정책을 유지한다. 신규 등록 201, 이미 관심 등록된 경우 200이다.
- stocks/watchlist_stocks와 FK·복합 PK·`(market, stock_code)` 유일 제약을 유지한다. symbol → stock_code 컬럼명 변경만 수행한다. 기존 데이터와 stock_id/FK를 유지하며 별도 키·의존성은 추가하지 않는다.
- 관심 목록 GET과 DELETE에서는 키움·Redis를 호출하거나 종목 마스터를 생성하지 않는다. POST에서 DB miss가 난 경우에만 메타데이터 확보 경로에 진입한다.
- 현재가·등락률·랭킹은 stocks에 저장하지 않는다. 종목코드·이름·시장·업종만 저장한다.
- 기존 키움 토큰 발급·transport를 재사용하되 종목 마스터 조회는 Redis 메타데이터 캐시를 거치지 않는다. 키 값을 코드·문서·로그에 넣지 않는다.
- B안은 요청된 종목만 적재하는 방식이다. 전체 종목 일괄 수집, 정기 갱신, 기존 종목명·업종 덮어쓰기는 이번 범위에 없다.

## 현재 코드와 변경 이유

- `watchlist.service.WatchlistService.add()`는 @Transactional 안에서 사용자를 잠근 뒤 stocks를 조회하고, 없으면 404 STOCK_NOT_FOUND를 반환한다.
- 종목 마스터는 DB에 없을 때만 키움 서버에서 직접 조회한다. 기존 StockMetadataCache 및 다른 호출자의 캐시 사용은 변경하지 않는다.
- `KiwoomMarketClient.stockDetails()`는 이름·업종만, `stockInfo()`는 시장정보만 반환한다. 둘을 연속 호출해 외부 조회를 중복하지 않고 마스터용 메서드를 추가한다.
- `stocks.market`는 VARCHAR(10)이며 테스트/기존 수동 데이터에 KOSPI 같은 문자열이 있다. 신규 자동 적재는 공급자의 marketCode 원문을 저장한다. 검증되지 않은 시장명 매핑을 만들지 않고 기존 행의 표현도 변경하지 않는다. 관심 응답에는 market이 없으므로 응답 계약은 유지된다.
- 기존 행은 stock_code로 먼저 조회하므로 과거 시장명 표현도 그대로 재사용한다. 같은 stock_code의 복수 행은 기존처럼 409 AMBIGUOUS_STOCK_CODE로 거부하며 임의 선택하거나 새 행을 만들지 않는다.

## 호출 흐름과 트랜잭션

```text
POST /api/v2/users/me/watchlist {stock_code}
 → Security 인증·CSRF → WatchlistAddRequest 변환·@Valid
 → watchlist.controller.WatchlistController.add(jwt, request)
 → watchlist.service.WatchlistService.add(userId, code) [외곽 트랜잭션 없음]
    → 코드 형식·사용자 존재 사전 확인 [짧은 읽기 트랜잭션, 잠금 없음]
    → StockCatalogService.resolveStockId(code)
       → StockRepository.findAllByStockCode(code) [짧은 읽기]
       ├─ 1행: stock_id 반환
       ├─ 2행 이상: 409 AMBIGUOUS_STOCK_CODE
       └─ 0행:
          → KiwoomMarketClient.stockMaster(code) [DB 트랜잭션 없음]
             → stockInfoResponse(code) → transport.query(ka10100)
             → 기존 인증으로 키움 서버 직접 조회
             → StockMaster 필드 검증
          → 새 짧은 트랜잭션: stock_code 재조회 후 없으면 stocks 저장·flush·커밋
          → 유일 제약 경합이면 롤백 완료 후 새 읽기 트랜잭션에서 재조회
    → 짧은 쓰기 트랜잭션
       → requireUser(userId, true): 사용자 행 잠금·존재 재확인
       → stock_code 재조회: 정확히 1행인지 확인하고 사용할 Stock 결정
       → (userId, stockId) 존재 확인
       → 있으면 false / 없으면 persist(new Watchlist(userId, stock)) 후 true
 → true면 201 / false면 200, 기존 WatchlistAddResponse 반환
```

컨트롤러는 JWT sub의 사용자 ID와 요청 종목코드를 기존대로 add()에 전달한다.
add()는 잠금 없이 사용자를 먼저 확인한 뒤 resolveStockId()를 호출한다. 이때 외곽 @Transactional을 제거해 키움 대기 중 DB 트랜잭션을 유지하지 않는다.
resolveStockId()는 기본정보를 확보하고 종목 저장을 커밋한 뒤 ID를 반환한다. 마지막 관심 등록 트랜잭션에서는 사용자 잠금 아래 종목을 다시 읽어 중복 등록을 직렬화한다.
같은 트랜잭션 안에서 유일 제약 오류를 catch한 뒤 계속 저장하지 않는다. 이미 실패 표시된 트랜잭션이므로 반드시 롤백을 마친 바깥에서 새 트랜잭션으로 재조회한다.

종목 마스터는 모든 사용자가 공유하므로 관심 등록이 이후 실패해도 정상 확보된 stocks 행은 남길 수 있다. 대신 watchlist_stocks 행은 성공한 등록 트랜잭션에서만 생성한다.
최종 재조회에서 종목이 삭제되어 없으면 404 STOCK_NOT_FOUND, 복수 행이면 409로 거부한다. 일반적인 DB miss는 더 이상 즉시 404가 아니다.

## Review Focus

1. DB hit에서 키움 장애가 발생해도 관심 등록·조회가 정상이어야 한다. Task 2·3 테스트.
2. 다른 사용자가 같은 미등록 종목을 동시에 등록하면 stocks 1행과 사용자별 관심 1행으로 수렴해야 한다. Task 2·3 실제 트랜잭션 테스트.
3. 키움 타임아웃·잘못된 응답을 미존재 종목 404로 오인하거나 잘못된 마스터로 저장하면 안 된다. Task 1·2 테스트.
4. 외부 조회가 느린 동안 같은 사용자의 기존 관심 삭제 등 DB 작업을 사용자 잠금으로 막으면 안 된다. Task 3 latch 테스트.
5. 유일 제약 이외의 DB 오류를 중복 등록 성공으로 숨기면 안 되고, 사용자 삭제·종목 중복 발생도 마지막 트랜잭션에서 확인해야 한다. Task 2·3 테스트.

## Task 1: 키움 직접 조회 종목 마스터 응답 추가

**Files:**
- Modify: `src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java`
- Test: `src/test/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClientTests.java`


**Interfaces:** `public StockMaster stockMaster(String stockCode)` 및 내부 public record `StockMaster(String stockCode, String stockName, String marketCode, String sector)`를 추가한다. 기존 stockDetails()/stockInfo()의 계약은 변경하지 않는다.

- [ ] 테스트 먼저 작성: 정상 응답 `005930/삼성전자/0/반도체`, 업종 누락·공백이면 sector=null, 요청 코드와 응답 코드 불일치·이름/시장 누락·길이 초과·잘못된 타입은 실패.
- [ ] 이름은 trim 후 1~100자, marketCode는 trim 후 1~10자, 업종은 trim 후 최대 100자로 검증한다. 길이를 임의 절단하지 않는다. 종목코드는 기존 숫자/영문 대문자 6자리 규칙이다.
- [ ] 실패 테스트를 실행해 신규 진입점 부재를 확인한 뒤, stockInfoResponse()를 한 번 호출해 네 필드를 구성한다. 캐시 유무와 무관하게 직접 조회한다.
- [ ] 캐시가 주입되어 있어도 Redis를 호출하지 않고 transport.query()를 한 번 호출하는지, 공급자 실패 시 정상 데이터로 위장하지 않는지 검증한다.
- [ ] 실행: `.\gradlew.bat test --tests '*KiwoomMarketClientTests' --tests '*StockMetadataCacheTests' --console=plain` → 실패 0. 실제 키움 API·키를 호출하지 않는다.

## Task 2: DB 우선 종목 확보 및 동시 적재 처리

**Files:**
- Create: `src/main/java/com/stock_spoon/river_be/watchlist/service/StockCatalogService.java`
- Modify: `src/main/java/com/stock_spoon/river_be/watchlist/repository/StockRepository.java`
- Create: `src/test/java/com/stock_spoon/river_be/watchlist/StockCatalogServiceTests.java`
- Create: `src/test/java/com/stock_spoon/river_be/watchlist/StockCatalogConcurrencyTests.java`

**Interfaces:** `public long resolveStockId(String code)`. 기존 findAllByStockCode()을 우선 사용하고, 경합 진단용 `Optional<Stock> findByMarketAndStockCode(String market, String stockCode)`를 추가한다. PlatformTransactionManager로 읽기/쓰기 TransactionTemplate을 구성하고 독립 트랜잭션(REQUIRES_NEW) 경계를 명시한다.

- [ ] DB hit, DB ambiguity, miss 후 저장, 업종 null, 외부 실패 시 마스터 미저장 테스트를 작성하고 실패를 확인한다. DB hit에서는 market.stockMaster()를 호출하지 않는다.
- [ ] 외부 메서드 호출 시 실제 트랜잭션이 활성화되지 않았음을 test callback에서 확인한다. 반환된 원격 필드 검증은 Task 1에서 담당하며 마스터 서비스는 code 일치와 DTO 유효성 계약을 따른다.
- [ ] 외부 조회 이후 새 쓰기 트랜잭션에서 stock_code을 재조회한다. 이미 있으면 재사용하고, 없으면 Stock 생성 후 saveAndFlush()로 저장한다. 커밋 실패도 호출 밖으로 전파되도록 한다.
- [ ] DataIntegrityViolationException은 쓰기 트랜잭션이 종료된 밖에서 처리한다. 새 읽기에서 `(marketCode, code)` 행이 실제 존재하고 stock_code 조회도 유일할 때만 경합 성공으로 간주한다. 해당 행이 없으면 원래 DB 예외를 전파한다. 무한 재시도나 모든 DB 예외 무시는 금지한다.
- [ ] 키움의 IllegalStateException·RestClientException은 `503 STOCK_INFO_UNAVAILABLE`의 WatchlistException으로 변환한다. 공급자의 일반 실패 응답으로 404를 추측하지 않는다. 정확한 미존재 응답 코드 매핑은 별도 근거가 있을 때만 추가한다.
- [ ] 동시 DB miss 두 요청을 barrier로 맞춘 뒤 실제 H2 트랜잭션에서 stock 1행·같은 ID 반환을 검증한다. 경합이 아닌 길이·DB 오류는 성공으로 삼지 않는지도 검증한다. 이 테스트에는 외곽 @Transactional을 적용하지 않는다.
- [ ] 로그는 `event=watchlist_stock_resolved source=database|kiwoom stockCode stockId`, 실패는 `event=watchlist_stock_resolution_failed stage code causeType stockCode`를 사용한다. kiwoom는 직접 키움 조회를 뜻한다. 원문 응답·키·토큰·예외 원문은 기록하지 않는다.
- [ ] 실행: `.\gradlew.bat test --tests '*StockCatalog*Tests' --console=plain` → 실패 0.

## Task 3: 관심 등록 연결 및 기존 계약 회귀 검증

**Files:**
- Modify: `src/main/java/com/stock_spoon/river_be/watchlist/service/WatchlistService.java`
- Modify: `src/test/java/com/stock_spoon/river_be/watchlist/WatchlistControllerTests.java`
- Modify: `src/test/java/com/stock_spoon/river_be/watchlist/WatchlistConcurrencyTests.java`
- Modify: `docs/watchlist-api.md`

**Interfaces:** 기존 `boolean add(long userId, String code)`는 유지한다. StockCatalogService와 PlatformTransactionManager를 주입한다. add() 외곽 @Transactional만 제거하고, 사전 사용자 확인은 readOnly TransactionTemplate, 최종 사용자 잠금·관심 저장은 쓰기 TransactionTemplate에서 실행한다. list()/remove()의 기존 트랜잭션은 유지한다.

- [ ] 기존 WatchlistControllerTests의 클래스 외곽 @Transactional을 제거하고 fixture 저장을 실제 커밋하도록 조정한다. 마스터의 독립 트랜잭션이 테스트의 미커밋 fixture를 읽지 못하는 문제를 피한다. @AfterEach에서 해당 테스트가 만든 사용자·관심 행·종목 ID만 FK 순서에 맞춰 정리하고, 다른 테스트의 데이터를 일괄 삭제하지 않는다.
- [ ] MockMvc 실패 테스트 추가: 미등록 종목을 키움 mock으로 확보해 201, 재등록은 200, GET의 이름·업종 일치, stocks 1행/관심 1행. 기존 DB hit는 키움 장애와 관계없이 성공.
- [ ] 현재 `999999`가 DB에 없으면 무조건 404라는 테스트를 새 정책에 맞게 바꾼다. 외부 실패 mock은 503, 잘못된 입력은 기존 400, 모호한 코드 409·미인증/CSRF 계약은 유지한다.
- [ ] 최종 쓰기에서는 사용자 잠금을 다시 잡고 stock_code을 재조회한다. 정확히 1행인 Stock으로 기존 복합 PK 중복 검사를 수행하고 EntityManager.persist()를 재사용한다. 중간 삭제는 404, 복수 행은 409로 관심 저장 없이 종료한다.
- [ ] 사용자 존재 확인 실패 시 외부 호출 없음, 외부 대기 중 사용자 삭제 시 최종 등록 거부를 검증한다. 이미 적재된 정상 공유 마스터는 남을 수 있다는 정책도 검증한다.
- [ ] 같은 사용자의 동시 최초 등록은 201/200, 다른 사용자의 동시 최초 등록은 각 201이며 master 1행/사용자별 관심 1행을 실제 트랜잭션으로 확인한다.
- [ ] 키움 mock을 latch로 지연시킨 동안 다른 스레드에서 해당 사용자의 기존 관심 삭제가 완료되는지 확인해 외부 호출 중 사용자 잠금 부재를 검증한다. GET·DELETE에서 종목 적재·외부 호출이 없고 기존 등록순 정렬·사용자 격리가 유지되는지도 확인한다.
- [ ] 문서에 DB→키움 직접 조회→master 저장→관심 등록 호출 흐름, 트랜잭션 경계, 신규 시장코드 저장 방식, 503 정책·공유 마스터 잔존 가능성을 설명한다. 현재가 조회와 정기 갱신을 구현했다고 쓰지 않는다.
- [ ] 실행: `.\gradlew.bat test --tests '*Watchlist*Tests' --tests '*StockCatalog*Tests' --tests '*KiwoomMarketClientTests' --tests '*StockMetadataCacheTests' --console=plain` → 실패 0.
- [ ] 전체 `.\gradlew.bat build --build-cache --console=plain`, `git diff --check` 및 최종 코드 검토를 수행한다. H2 검증과 실제 MySQL·키움 연동 검증을 구분해 보고한다. 실제 키 유효성은 자동 테스트 통과로 보장하지 않는다.

## 완료 기준과 범위

| 상황 | 결과 |
|---|---|
| DB에 종목 1행 있음 | 키움 미호출, 기존 행으로 관심 등록 |
| DB에 없음, 메타데이터 정상 | stocks 저장 후 관심 등록, 신규 201 |
| 기존 관심 재등록 | 동일 등록 유지, 200 |
| 업종 없음 | sector=null로 등록·응답 |
| DB에 동일 stock_code 복수 행 | 409 AMBIGUOUS_STOCK_CODE, 외부 조회·신규 적재 없음 |
| 키움 실패/불완전한 메타데이터 | 503 STOCK_INFO_UNAVAILABLE, 잘못된 master·관심 행 미생성 |
| 동시 최초 등록 | master 중복 없이 사용자별 관심 등록 수렴 |
| 외부 대기 중 사용자 삭제 | 관심 행 미생성, 정상 master는 남을 수 있음 |
| DB 장애 | 중복 성공으로 숨기지 않고 실패 전파 |

계획만 작성했다. 실제 종목 적재·구현·테스트 실행·키움 키 사용·커밋은 하지 않았다.

## 컬럼명 변경 적용

- Stock.stockCode / getStockCode(), findAllByStockCode(), 삭제 JPQL을 함께 변경했다.
- 신규 DB는 db/watchlist.sql을 사용한다.
- 기존 MySQL은 애플리케이션 중지 후 db/rename-stocks-symbol-to-stock-code.sql을 먼저 적용하고 새 버전을 배포한다. Hibernate update에 컬럼명 변경을 맡기지 않는다.
- 이 SQL은 수동 적용용이며 운영 DB에는 아직 실행하지 않았다.
