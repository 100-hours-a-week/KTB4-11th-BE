# v2 관심 종목 구현·DB 변경·호출 흐름

갱신일: 2026-10-22 / 구현 기준: `feat/184-stock-data-watchlist-fix`의 종목 마스터 자동 적재 변경.

이 문서는 현재 구현을 설명한다. DB 준비 SQL은 작성했지만 실제 운영 DB에 실행하지 않았다. FE 화면과 시세 서버 연결도 이번 백엔드 변경에 포함하지 않았다.

## 1. 기능 정책과 책임

- 관심 목록은 사용자 단위다. 계좌를 전환해도 같은 목록을 사용하고 요청·응답에 계좌 ID를 포함하지 않는다.
- 사용자 ID는 인증된 JWT의 subject에서 가져온다. FE는 등록할 종목코드만 보낸다.
- BE는 관심 목록과 종목 메타데이터를 관리한다. FE가 시세 서버의 종목코드 `code`와 관심 API의 `stock_code`를 대조한다.
- 현재가·등락률·랭킹은 FE가 시세 서버에서 직접 조회한다. 관심 등록 시 DB에 없는 종목만 키움 기본정보를 조회한다. 목록·삭제는 외부 조회를 하지 않는다.
- FE의 하트 색상, 관심 탭 갱신, 계좌 전환 및 로그아웃 시 캐시 처리는 연동해야 할 작업이다.

## 2. 코드 위치

Java 기본 패키지는 `com.stock_spoon.river_be`이며 아래 표의 패키지는 그 하위다.

| 패키지·클래스 | 역할 |
|---|---|
| [watchlist.controller.WatchlistController](../src/main/java/com/stock_spoon/river_be/watchlist/controller/WatchlistController.java) | GET/POST/DELETE 수신, 인증 사용자 ID 전달, HTTP 상태 결정 |
| [watchlist.service.WatchlistService](../src/main/java/com/stock_spoon/river_be/watchlist/service/WatchlistService.java) | 사용자 확인, 종목 조회, 중복 처리, 트랜잭션·잠금, 목록 구성 |
| [watchlist.service.StockCatalogService](../src/main/java/com/stock_spoon/river_be/watchlist/service/StockCatalogService.java) | DB 우선 종목 확보, 키움 조회, 독립 트랜잭션에서 마스터 저장·충돌 후 재조회 |
| [market.kiwoom.KiwoomMarketClient](../src/main/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketClient.java) | stockMaster(code)에서 종목정보 캐시 없이 ka10100 조회·검증 |
| [watchlist.repository.StockRepository](../src/main/java/com/stock_spoon/river_be/watchlist/repository/StockRepository.java) | 종목코드로 마스터 조회 |
| [watchlist.repository.WatchlistRepository](../src/main/java/com/stock_spoon/river_be/watchlist/repository/WatchlistRepository.java) | 사용자별 관심 조회·존재 확인·삭제 |
| [watchlist.entity.Stock](../src/main/java/com/stock_spoon/river_be/watchlist/entity/Stock.java) | stocks 테이블 매핑 |
| [watchlist.entity.Watchlist](../src/main/java/com/stock_spoon/river_be/watchlist/entity/Watchlist.java) | watchlist_stocks 매핑, 등록 시각 생성 |
| [watchlist.entity.WatchlistId](../src/main/java/com/stock_spoon/river_be/watchlist/entity/WatchlistId.java) | 사용자·종목 복합 ID, equals/hashCode |
| [watchlist.dto.WatchlistAddRequest](../src/main/java/com/stock_spoon/river_be/watchlist/dto/WatchlistAddRequest.java) | stock_code 역직렬화·형식 검증 |
| [watchlist.dto.WatchlistAddResponse](../src/main/java/com/stock_spoon/river_be/watchlist/dto/WatchlistAddResponse.java) | 등록 결과와 종목코드 |
| [watchlist.dto.WatchlistResponse](../src/main/java/com/stock_spoon/river_be/watchlist/dto/WatchlistResponse.java) | 목록 및 Item 응답 |
| [watchlist.service.WatchlistException](../src/main/java/com/stock_spoon/river_be/watchlist/service/WatchlistException.java) | 업무 오류의 상태·코드·메시지 보관 |
| [watchlist.controller.WatchlistExceptionHandler](../src/main/java/com/stock_spoon/river_be/watchlist/controller/WatchlistExceptionHandler.java) | WatchlistException을 JSON 오류로 변환 |
| [config.SecurityConfig](../src/main/java/com/stock_spoon/river_be/config/SecurityConfig.java) | 기존 쿠키 JWT 인증 재사용, DELETE CORS 및 CSRF 수정 |

Service의 `list`, `add`, `remove`, `requireUser`, `validate`에는 역할 설명 주석을 추가했다. 구조 생성용 package-info.java는 제거했다.

## 3. 관심 등록 API

```text
POST /api/v2/users/me/watchlist
  → SecurityConfig의 필터 체인: 쿠키 JWT 인증·CSRF 검사
  → WatchlistAddRequest.StrictStockCode.deserialize(parser, context)
  → @Valid: @NotBlank, @Pattern 검사
  → watchlist.controller.WatchlistController.add(jwt, request)
  → watchlist.service.WatchlistService.add(Long.parseLong(jwt.getSubject()), request.stockCode())
    → validate(code)
    → requireUser(userId, false) [짧은 읽기 트랜잭션, 잠금 없음]
    → StockCatalogService.resolveStockId(code)
      → StockRepository.findAllByStockCode(code) [독립 읽기 트랜잭션]
        → 1건: 기존 ID / 2건 이상: 409
        → 0건: KiwoomMarketClient.stockMaster(code) [DB 트랜잭션 없음]
          → stockInfoResponse(code) → KiwoomRestTransport.query(ka10100, /api/dostk/stkinfo, {stk_cd: code})
          → 새 쓰기 트랜잭션에서 코드 재조회 후 Stock 저장·flush·커밋
          → 유일 제약 충돌은 롤백 후 새 읽기에서 동일 시장·코드의 행 확인
    → addResolved(userId, code) [짧은 쓰기 트랜잭션]
    → requireUser(userId, true)
      → EntityManager.find(User.class, userId, PESSIMISTIC_WRITE)
    → watchlist.repository.StockRepository.findAllByStockCode(code)
      → 0건: 404 / 2건 이상: 409
    → WatchlistRepository.existsById(new WatchlistId(userId, stock.getId()))
      → 이미 등록: false 반환
      → 미등록: EntityManager.persist(new Watchlist(userId, stock)), true 반환
    → 트랜잭션 커밋
  → WatchlistAddResponse("success", request.stockCode())
  → 신규 201 Created / 기존 등록 200 OK
```

POST 요청의 JSON은 `WatchlistAddRequest`로 변환된다. 중첩 클래스 `StrictStockCode.deserialize()`는 JSON 문자열만 받는다. 숫자 `123456`을 문자열로 자동 변환하지 않는다. 이어서 `@Valid`가 null·공백·잘못된 형식을 검사한다. 인증 필터에서 토큰을 확인하는 것과, Controller의 `@AuthenticationPrincipal Jwt jwt` 인자에 인증 결과를 주입하는 것은 별도 단계다.

Controller의 `add(jwt, request)`는 JWT subject를 long 사용자 ID로 변환하고, DTO의 `stockCode()`와 함께 Service의 `add(userId, code)`에 전달한다. 계좌 ID나 FE가 지정한 사용자 ID는 사용하지 않는다.

Service는 코드와 사용자 존재를 잠금 없이 먼저 확인한 뒤 StockCatalogService.resolveStockId(code)를 호출한다. DB에 종목이 있으면 재사용하고, 없으면 KiwoomMarketClient.stockMaster(code)가 종목코드·종목명·시장코드·업종명을 가져온다. 이름·시장 필수값과 길이를 검증하며 업종 누락·공백은 null로 저장한다. 시장은 공급자의 marketCode 원문을 사용하고 기존 KOSPI 등 데이터는 그대로 재사용한다.

키움 대기 중에는 DB 트랜잭션·사용자 잠금을 유지하지 않는다. 마스터 저장이 커밋된 뒤 addResolved(userId, code)가 사용자 행을 잠그고 종목을 다시 조회한다. 조회된 종목이 하나일 때 내부 stock_id를 얻고 `(userId, stockId)` 중복을 확인한다. 신규 관심 행은 **EntityManager.persist()**로 저장하고 생성자가 등록 시각을 설정한다. 그 사이 사용자가 삭제됐으면 관심 등록을 거부하며 정상 확보된 공유 stocks 행은 남을 수 있다.

종목정보 Redis 캐시는 사용하지 않는다. 일반 서버는 기존 RemoteKiwoomRestTransport.query()로 담당 서버의 /internal/kiwoom/query에 요청하고, 담당 서버가 기존 인증·호출 제한으로 키움을 조회한다. 담당 서버 탐색용 Redis는 계속 필요하다. 별도 일괄 적재·정기 갱신·기존 마스터 덮어쓰기는 수행하지 않는다.

같은 사용자의 등록·해제는 사용자 행 잠금으로 직렬화한다. 두 등록 요청이 동시에 들어오면 먼저 처리된 요청이 커밋된 후 다음 요청이 중복 여부를 확인한다. 복합 PK도 같은 사용자·종목의 중복 행을 막는다. 트랜잭션 커밋이 실패하면 성공 응답을 반환하지 않는다.

요청:

```json
{"stock_code":"005930"}
```

성공 본문(201과 200 공통):

```json
{"message":"success","stock_code":"005930"}
```

## 4. 관심 목록 조회 API

```text
GET /api/v2/users/me/watchlist
  → 인증된 Jwt를 WatchlistController.list(jwt)에 주입
  → WatchlistService.list(Long.parseLong(jwt.getSubject()))
    → requireUser(userId, false): EntityManager.find(User.class, userId)
    → WatchlistRepository.list(userId)
      → Watchlist와 Stock을 join fetch
      → userId 일치 조건 / createdAt DESC, stock.id DESC 정렬
    → Stock 값을 WatchlistResponse.Item으로 변환
  → WatchlistResponse("success", items)
  → 200 OK
```

Controller의 `list(jwt)`는 인증 사용자의 ID만 Service에 전달한다. Service의 `list(userId)`는 읽기 전용 트랜잭션에서 사용자 존재를 확인한다. 이때는 변경용 잠금을 걸지 않는다.

Repository의 `list(userId)`는 해당 사용자의 관심 행과 종목을 한 번에 조회한다. Service는 각 Stock의 stock_code·name·sector를 각각 stock_code·stock_name·sector로 옮겨 응답을 만든다. 가장 최근 등록한 항목이 먼저 나오고 등록 시각이 같으면 내부 종목 ID가 큰 항목이 먼저 나온다. 페이지네이션과 서버 업종 필터 파라미터는 현재 없다.

```json
{
  "message": "success",
  "watchlists": [
    {"stock_code":"005930","stock_name":"삼성전자","sector":"반도체"}
  ]
}
```

관심 종목이 없으면 `{"message":"success","watchlists":[]}`다. sector는 null일 수 있다. account_id·현재가·등락률은 반환하지 않는다.

## 5. 관심 해제 API

```text
DELETE /api/v2/users/me/watchlist/{stockCode}
  → 인증·CSRF 검사
  → WatchlistController.remove(jwt, stockCode)
  → WatchlistService.remove(Long.parseLong(jwt.getSubject()), stockCode)
    → validate(code)
    → requireUser(userId, true): 사용자 행 잠금
    → WatchlistRepository.remove(userId, code)
      → 해당 사용자의 행 중, 종목 stock_code이 code인 행만 삭제
    → 트랜잭션 커밋
  → {"message":"delete_success"}
  → 200 OK
```

Controller는 경로의 값을 `stockCode`, 인증 결과를 `jwt`로 받는다. Service의 `remove(userId, code)`가 코드 형식과 사용자 존재를 확인하고, Repository에 사용자 ID와 종목코드를 모두 전달한다.

Repository는 `user_id`와 종목코드 조건을 모두 사용하므로 다른 사용자의 관심 등록을 삭제하지 않는다. 삭제 건수는 int로 반환되지만 Service는 건수를 응답 분기에 사용하지 않는다. 이미 해제된 종목이나 마스터에 없는 코드도 형식이 올바르면 200으로 처리한다. 같은 코드가 여러 마스터 행에 존재하면 해당 사용자의 그 코드에 연결된 관심 행들을 삭제한다. 종목 마스터 자체는 삭제하지 않는다.

## 6. 전달값·식별자 정리

| 값 | 출처 | 사용처 |
|---|---|---|
| userId | 인증 Jwt.getSubject() → Long.parseLong | 사용자 확인, 관심 조회·중복 확인·삭제 조건 |
| code | 등록 JSON의 stock_code 또는 삭제 URL의 stockCode | 마스터 stock_code 조회, 삭제 대상 확인 |
| stockId | 조회된 Stock.getId() | 관심 복합 PK와 stock_id FK |
| created | WatchlistService.add 반환 boolean | Controller가 201 또는 200 결정 |
| createdAt | new Watchlist에서 Instant.now() | 등록 시각 저장·조회 정렬 |

예: 사용자 7이 `005930`을 등록하고 마스터의 stock_id가 42면 `(user_id=7, stock_id=42)` 한 행이 생긴다. 사용자 8도 독립적으로 같은 종목을 등록할 수 있다. 계좌 생성·전환은 이 관계에 관여하지 않는다.

## 7. DB 준비 및 테이블 정의

[db/watchlist.sql](../db/watchlist.sql)은 MySQL 8.x용 수동 준비 SQL이다. 코드에 엔티티 매핑을 추가한 것과 운영 DB에 테이블이 생긴 것은 구분한다. 이 작업에서 SQL을 실행하거나 실제 스키마를 조회하지 않았다.

### stocks — 종목 마스터

| 컬럼 | SQL 타입 | NULL | 제약·역할 |
|---|---|---|---|
| stock_id | BIGINT | 불가 | AUTO_INCREMENT PK, 내부 식별자 |
| stock_code | VARCHAR(20) | 불가 | 공개 종목코드, 관심 API 입력은 숫자·영문 대문자 6자리 |
| name | VARCHAR(100) | 불가 | 종목명 |
| market | VARCHAR(10) | 불가 | 소속 시장, 현재 Entity에 enum 검증 없음 |
| sector | VARCHAR(100) | 가능 | 업종 표시·FE 필터용 데이터 |

`uk_stocks_market_stock_code(market, stock_code)` 유일 제약을 둔다. stock_code 단독 유일 제약은 없으므로 등록 API가 조회 결과 2건 이상을 409로 처리한다.

기존 DB가 `symbol` 컬럼을 사용한다면 애플리케이션을 중지하고 `db/rename-stocks-symbol-to-stock-code.sql`을 적용한 뒤 새 버전을 배포한다. 기존 값과 stock_id/FK는 유지한다. 신규 DB는 `db/watchlist.sql`을 사용한다. Hibernate 자동 스키마 갱신에 컬럼명 변경을 맡기지 않는다.

신규 테이블용 SQL을 제공했으며 기존 운영 stocks를 대체하거나 축소하지 않는다. 기존 stocks가 있으면 CREATE TABLE IF NOT EXISTS는 필드를 보완하지 않는다. sector가 없을 때 적용할 `ALTER TABLE stocks ADD COLUMN sector VARCHAR(100) NULL`은 SQL 주석에만 있다. 실제 테이블·기존 제약·데이터와 비교한 후 필요한 변경만 적용해야 한다. 기존 마스터가 stock_code 등 다른 컬럼을 사용한다면 매핑부터 대조해야 한다.

### watchlist_stocks — 사용자별 관심 관계

| 컬럼 | SQL 타입 | NULL | 제약·역할 |
|---|---|---|---|
| user_id | BIGINT | 불가 | 복합 PK, users.user_id FK |
| stock_id | BIGINT | 불가 | 복합 PK, stocks.stock_id FK |
| created_at | DATETIME(6) | 불가 | DB 기본값 CURRENT_TIMESTAMP(6), 앱 저장은 생성자 시각 |

기본 키는 `(user_id, stock_id)`다. SQL의 FK에는 ON DELETE CASCADE가 없다. 계좌 FK는 없다. JPA는 stock을 ManyToOne으로 매핑하고 userId는 Long 값으로 매핑하므로, SQL에 작성한 사용자 FK와 JPA 자동 생성 스키마는 동일하다고 단정하지 않는다. 운영에서는 실제 FK 존재를 확인해야 한다.

종목·사용자 물리 삭제 전에는 관심 행 정리가 필요할 수 있다. 이번 기능은 사용자 탈퇴·종목 삭제 정책을 추가하지 않았다. 마스터가 비어 있으면 관심 등록 요청의 종목만 키움에서 확보해 저장한다. 일괄 수집·정기 갱신·운영 샘플 데이터 삽입은 하지 않는다.

## 8. 보안 변경과 FE 영향

[SecurityConfig.securityFilterChain()](../src/main/java/com/stock_spoon/river_be/config/SecurityConfig.java)에서 두 항목을 변경했다.

1. CORS 허용 메서드에 DELETE를 추가했다. 기존 허용 Origin, 자격 증명 및 헤더 정책은 유지한다. PUT은 추가하지 않았다.
2. 쿠키 JWT 요청도 CSRF 보호를 받도록 자동 예외를 해제했다. 기존 Resource Server 설정은 커스텀 resolver로 JWT를 찾은 요청을 CSRF 검사에서 제외했다. 테스트에서 CSRF 토큰 없는 POST가 201로 처리되어 원인을 확인했다. 현재는 http.build() 이후 CsrfFilter의 검사 조건을 DEFAULT_CSRF_MATCHER로 되돌린다.

`AUTH_CSRF_DISABLED` 기본값은 false다. false이면 GET/HEAD/TRACE/OPTIONS를 제외한 변경 요청을 검사한다. 기존 true 옵션의 /api/** CSRF 제외 동작은 유지한다. 실제 배포 환경의 옵션 값은 이번 문서화에서 확인하지 않았다.

수정은 관심 API만이 아니라 같은 보안 필터 체인을 쓰는 기존 변경 API에도 영향을 준다. FE는 CSRF가 활성화된 환경에서 POST/PATCH/DELETE 등에 필요한 확인값을 보내야 한다.

FE 요청 순서:

1. 기존 `GET /api/v1/auth/csrf`에서 token·header_name을 받는다. 응답의 HttpOnly XSRF-TOKEN 쿠키는 브라우저가 저장한다.
2. 등록·해제 요청에 인증 쿠키·CSRF 쿠키가 전달되게 하고 응답의 token을 X-XSRF-TOKEN 헤더로 보낸다. 교차 Origin이면 credentials 설정과 허용 Origin이 맞아야 한다.
3. 인증과 CSRF는 별도 검사다. 토큰 없는 변경 요청은 CSRF 필터에서 먼저 403이 될 수 있으므로 모든 미인증 요청이 항상 401이라는 가정은 하지 않는다.

발견 화면에서 FE는 관심 코드 집합을 조회해 등록된 종목의 하트를 채운다. 빈 하트는 POST, 채운 하트는 DELETE를 호출한다. 성공 시 두 탭이 공유하는 관심 상태를 갱신하며 실패 시 기존 상태를 유지한다. 이 UI 동작은 권장 연동 흐름이며 FE 구현 완료를 의미하지 않는다.

## 9. 예외 처리

| 발생 위치·조건 | 최종 응답 |
|---|---|
| 인증 필터에서 인증 실패 | 기존 401 UNAUTHORIZED |
| CSRF 검증 실패 | 403 INVALID_CSRF_TOKEN |
| JSON 문법·타입 오류, @Valid 실패 | 기존 AuthExceptionHandler.invalidRequest(), 400 INVALID_REQUEST |
| Service.validate에서 코드 형식 불일치 | WatchlistExceptionHandler.handle(), 400 INVALID_STOCK_CODE |
| Service.requireUser에서 사용자 없음 | 401 UNAUTHORIZED |
| 마스터 최초 조회 0건 | 키움에서 확보 후 저장·관심 등록 |
| 키움 실패·잘못된 필수 응답 | 503 STOCK_INFO_UNAVAILABLE |
| 확보 후 최종 재조회에서 종목이 삭제됨 | 404 STOCK_NOT_FOUND |
| add에서 마스터 2건 이상 | 409 AMBIGUOUS_STOCK_CODE |

업무 오류 본문은 `{"code":"STOCK_NOT_FOUND","message":"종목을 찾을 수 없습니다."}` 같은 형태다. 코드 형식 오류는 등록의 DTO 단계와 삭제의 Service 단계에 따라 오류 코드가 다르다. 예상하지 못한 DB 장애·잠금 타임아웃은 관심 전용 오류로 변환하지 않는다. 관심 기능에 별도 자동 재시도는 없다.

## 10. 검증 결과와 한계

이번 구현의 전체 `gradlew.bat build --build-cache --console=plain` 결과는 테스트 438개 중 422개 통과·16개 건너뜀·실패 0개이며 SpotBugs도 통과했다. 이 빌드와 별도로 아래 키움 실연동 테스트 1개를 명시적으로 활성화해 실행했고 통과했다.

| 테스트 파일 | 검증한 내용 |
|---|---|
| [WatchlistDtoTests](../src/test/java/com/stock_spoon/river_be/watchlist/WatchlistDtoTests.java) | 코드 형식·선행 0·JSON 필드·빈 목록 |
| [WatchlistControllerTests](../src/test/java/com/stock_spoon/river_be/watchlist/WatchlistControllerTests.java) | 등록 201·중복 200, 목록 메타데이터, 사용자 격리, 반복 삭제, 잘못된 요청·숫자 JSON 거절, 없는 종목·모호한 코드, 인증·CSRF·DELETE preflight |
| [WatchlistConcurrencyTests](../src/test/java/com/stock_spoon/river_be/watchlist/WatchlistConcurrencyTests.java) | 서로 다른 트랜잭션의 동시 등록 두 건 중 신규 하나·중복 하나, 최종 관심 한 건 |
| [StockMasterTests](../src/test/java/com/stock_spoon/river_be/market/kiwoom/StockMasterTests.java) | 종목정보 캐시 미호출, 직접 조회, 필드·길이 검증, 업종 null |
| [StockCatalogServiceTests](../src/test/java/com/stock_spoon/river_be/watchlist/StockCatalogServiceTests.java) | DB 우선 사용, 외부 호출 중 트랜잭션 없음, 저장 경합, 별개 DB 오류 전파 |
| [WatchlistBackfillTests](../src/test/java/com/stock_spoon/river_be/watchlist/WatchlistBackfillTests.java) | 동일·다른 사용자의 동시 최초 등록, 키움 대기 중 기존 관심 삭제, 사용자 삭제 후 등록 거부 |
| [KiwoomMarketLiveTests.retrievesStockMasterDirectly](../src/test/java/com/stock_spoon/river_be/market/kiwoom/KiwoomMarketLiveTests.java) | 실제 키움 인증·삼성전자 종목정보 직접 조회 |

2026-10-22 실연동 테스트는 `KIWOOM_LIVE_TEST=true`에서 `gradlew.bat test --tests '*KiwoomMarketLiveTests.retrievesStockMasterDirectly' --console=plain`로 실행했다. `stockMaster("005930")`가 실제 응답 `stockCode=005930, stockName=삼성전자, marketCode=0, sector=전기/전자`를 반환했다. 테스트 1개 통과·실패 0개·건너뜀 0개다. 인증정보는 로컬 설정에서 읽고 출력하지 않았다. 단독 직접 조회 경로의 검증이며 운영 담당 서버 경유·Redis·운영 DB 저장까지 검증한 것은 아니다.

통합 테스트 DB는 H2의 MySQL 모드이며 JPA create-drop으로 생성한다. **운영 MySQL에서 SQL·FK·잠금을 검증한 결과는 아니다.** 계좌 전환 UI, FE 하트 동기화, 브라우저 쿠키/CSRF 실제 왕복, 시세 장애 상황의 화면은 실제 연동 테스트하지 않았다.

## 11. 운영 연결 전 남은 작업

- 운영 users·stocks·watchlist_stocks의 컬럼·키·기존 데이터와 db/watchlist.sql 대조.
- 필요한 테이블·sector 컬럼·복합 키·FK 및 기존 symbol 컬럼명 변경 적용. 키움 인증·담당 서버 연결을 실제 환경에서 확인.
- 업종 분류 기준과 sector=null 표시 방식을 FE와 합의.
- FE 관심 코드와 시세 코드 연결, 하트 및 관심 목록 상태 공유 검증.
- 배포 환경의 CSRF 옵션·허용 Origin·쿠키 설정을 확인하고 기존 변경 API까지 실제 연동 확인.

운영 DB 적용·운영 담당 서버 경유·푸시·배포는 수행하지 않았다. 실제 키움 단독 직접 조회 결과는 위 실연동 검증에 기록했다.
