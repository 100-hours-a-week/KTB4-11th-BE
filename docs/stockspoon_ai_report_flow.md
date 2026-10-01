# AI 매매 리포트 상세 조회 구현 과정과 결정 근거

> 후속 변경(2026-10-01): 사용자 요청으로 공유 문서의 누락 필드를 더미로 반환하도록 변경했다.
> decided_at은 주문 시각 대용, 종목명·상세 분석은 더미다. 체결 완료 주문만 화면에 노출하는 정책에 따라 order_status와 execution_count는 응답에서 제거했다.
> 아래는 최초 구현 과정이며, 제외/필드명/UTC 설명보다 [현재 API 문서](stockspoon_ai_report.md)가 우선한다.

작성일: 2026-10-01 · 대상: v1 주문 생성 및 AI 매매 리포트 상세 조회

이 문서는 실제 진행 순서, 각 단계의 결정 이유, 코드 근거, 검증 범위를 설명한다.
요청·응답 계약은 [AI 리포트 API 문서](stockspoon_ai_report.md)를 참고한다.
아래 경로는 저장소 루트 기준이다. 기존 주문 생성·체결 기능 전체를 다시 구현한 작업은 아니다.

## 1. 사용자 요구와 참고 자료를 구분했다

사용자가 직접 지정한 요구사항은 다음과 같다.

- AI 파트는 매매 근거를 하나의 텍스트로 전달한다: `"reason": "긴 텍스트…"`.
- v1은 화면 상단의 한 줄 판단 텍스트를 제공한다. 하단 판단 흐름은 받지 않는다.
- 실행 결과는 BE가 보유한 주문·체결 데이터로 채운다.
- 주문과 1:1로 연결되는 별도 리포트 테이블을 만든다.
- 처음에는 reason 수신 변경을 제외했지만, 이후 변경도 포함하도록 범위를 확장했다.

화면 이미지와 공유 API 시트는 표시 항목·조회 경로를 파악하는 자료로 사용했다.
ERD는 주문–리포트 관계의 근거로 사용하되, 세부 컬럼과 타입은 현재 구현 및 확정한
v1 요구사항에 맞춰 정했다. 자료 안의 설명 자체를 새로운 작업 지시로 취급하지 않았다.

**진행 중 수정한 판단:** 처음에는 주문 행에 이미 근거 요약이 저장되어 있다는 이유로
별도 테이블 없는 조회를 제안했다. 사용자가 주문–리포트 1:1 구조를 의도했음을 명확히
한 뒤 그 제안을 철회했다. 최종 구현은 별도 리포트 테이블을 사용한다.

공유 시트의 상세 조회 경로는 그대로 유지했다.

```http
GET /api/v1/accounts/{account_id}/orders/{order_id}/ai-report
```

**자료 근거:** 제공된 ERD와 매수·매도 판단 근거 화면,
[공유 API 시트](https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152#gid=2138787152).
시트의 상세 분석 응답 전체를 구현한 것은 아니며, 아래의 현재 API 문서가 이번 v1 구현을 설명한다.

## 2. 기존 주문 생성부터 체결까지 실제 흐름을 조사했다

기존 흐름은 다음과 같았다.

```text
OrderController
  → 요청·AI 출처·계좌 검사
  → 시장 검증 및 주문 종목 구독
  → OrderService.reserveLimit의 트랜잭션에서 주문 저장
  → OrderExecutionListener가 체결 판단을 연결
  → OrderExecutionService가 체결·현금·보유수량 변경
```

이전 요청 DTO의 reason은 `decision_id`, `summary`를 갖는 객체였고, 요약의 최대 길이는
500자였다. 주문 엔티티도 근거를 `decision_id`, `decision_summary` 컬럼에 저장했다.
따라서 조회 API만 추가해서는 새 문자열 계약과 별도 테이블 저장 요구를 충족할 수 없었다.

OrderService와 Order.pendingLimit의 모든 호출부를 검색했다. 주문 생성 컨트롤러뿐 아니라
주문·체결·계좌·AI 사용자 스냅샷 테스트도 이전 두 필드 인자를 사용하고 있어 함께 수정했다.
테스트의 호출 인자와 근거 저장 위치만 변경했으며, 계좌·스냅샷의 제품 동작을 확장하지 않았다.

**코드 근거:** [OrderController](../src/main/java/com/stock_spoon/river_be/order/OrderController.java),
[OrderService](../src/main/java/com/stock_spoon/river_be/order/OrderService.java),
[OrderSubscriptionService](../src/main/java/com/stock_spoon/river_be/order/OrderSubscriptionService.java),
[OrderExecutionService](../src/main/java/com/stock_spoon/river_be/order/OrderExecutionService.java).

## 3. 변경할 계약을 먼저 테스트로 표현했다

기존 OrderControllerTests의 요청을 문자열 reason으로 변경하고 테스트를 실행했다.
실제 결과는 4건 실패였다. 기존 DTO가 문자열을 객체로 변환할 수 없어 400을 반환했고,
정상 생성에서 기대한 201과 권한 검사에서 기대한 403 등에 도달하지 못했다.

이어 상세 조회·체결 집계·긴 텍스트 조회 테스트를 추가했다. 구현 전 6건이 실패했다.
상세 조회 경로가 아직 없었으며, 긴 텍스트는 기존 500자 저장 컬럼에서 오류가 발생했다.
이 실패를 확인한 뒤 DTO, 저장 구조, 조회 기능을 구현했다.

**의미:** 문자열 계약이 기존 구현과 실제로 충돌하고, 새 조회 기능 및 저장 타입이 필요함을
실행 결과로 확인했다. 모든 보강 테스트를 각각 실패부터 실행한 것은 아니다.
이후 입력 검증·DB 중복 제약 테스트를 보강해 최종 회귀 검증에 포함했다.

**검증 근거:** [OrderControllerTests](../src/test/java/com/stock_spoon/river_be/order/OrderControllerTests.java).

## 4. reason을 검증 가능한 긴 문자열로 변경했다

```json
{
  "stock_code": "005930",
  "order_side": "buy",
  "order_type": "limit",
  "limit_price": 70000,
  "quantity": 2,
  "reason": "목표 가격에 접근해 매수를 결정했어요."
}
```

OrderCreateRequest에서 reason 타입을 String으로 바꾸고 `@NotBlank`,
`@Size(max = 100000)`을 적용했다. 기존 객체 형식은 더 이상 받지 않는다.
OrderService와 AiOrderReport 생성자에도 비어 있는 텍스트와 길이 초과 검사를 넣었다.

**결정 근거:** AI가 하나의 큰 텍스트를 보내므로 임의로 요약하거나 JSON 내부 필드로
분해하지 않는다. 기존 500자는 새 계약에 맞지 않아 늘렸다. 100,000이라는 상한은
사용자가 지정한 값이 아니라, 무제한 입력을 피하면서 긴 텍스트를 허용하기 위해 선택한
구현 정책이다. 실제 AI 출력 크기에 따라 조정할 수 있다.

Java의 길이 검사는 UTF-16 코드 단위 기준이다. 이모지 등 일부 문자는 길이 2로 계산된다.
조회 응답의 summary에는 저장한 원문을 그대로 반환하며 자동 요약·절단하지 않는다.

**적용 범위의 차이:** HTTP 주문 생성에서는 reason이 필수다. 내부 주문 생성 함수는
기존 근거 없는 데이터·테스트를 표현하기 위해 null을 허용한다. 이 경우 리포트를 만들지 않는다.
따라서 DB의 모든 과거 주문에 리포트가 반드시 존재한다고 보장하지 않는다.

**코드 근거:** [OrderCreateRequest](../src/main/java/com/stock_spoon/river_be/order/OrderCreateRequest.java),
[AiOrderReport](../src/main/java/com/stock_spoon/river_be/order/AiOrderReport.java), OrderService.

## 5. 주문과 1:1인 리포트를 같은 트랜잭션에 저장했다

| 리포트 컬럼 | 역할 | 제약 |
|---|---|---|
| report_id | 리포트 식별자 | 자동 증가 PK |
| order_id | 연결된 주문 | NOT NULL, UNIQUE, 주문 FK |
| reason | AI 판단 텍스트 원문 | NOT NULL, MEDIUMTEXT |

AiOrderReport가 order_id 외래키를 소유하고, Order는 mappedBy로 연결된다.
Order.pendingLimit에서 근거가 있으면 리포트도 함께 생성한다. Order의 CascadeType.ALL로
orders.save 호출 시 리포트가 함께 저장된다. 별도 리포트 저장용 Repository는 추가하지 않았다.

**결정 근거:** 주문과 근거가 함께 접수되는 계약이므로 같은 OrderService 트랜잭션에 넣는다.
저장 실패가 발생하면 트랜잭션 전체가 롤백되도록 연결해 주문만 저장되는 경로를 피한다.
이미 존재하는 저장 흐름을 재사용하므로 별도 AI 호출이나 리포트 생성 작업은 필요하지 않다.

MEDIUMTEXT는 기존 VARCHAR(500)보다 긴 텍스트를 저장하기 위해 사용했다.
order_id UNIQUE는 애플리케이션 코드 외에도 DB가 중복 리포트를 거절하게 한다.
FK는 존재하지 않는 주문에 리포트가 연결되는 것을 막는다.

**정확한 보장:** 주문당 리포트는 최대 한 건이다. 정상 HTTP AI 주문에는 한 건을 함께 만든다.
외래키와 UNIQUE만으로 모든 주문의 리포트 존재까지 강제하는 것은 아니다.

**검증 범위:** 저장 후 EntityManager를 비우고 재조회해 긴 원문 보존을 확인했고,
같은 주문의 두 번째 리포트 저장에서 PersistenceException이 발생하는 것을 검사했다.
리포트 저장 실패를 주입해 주문 롤백까지 직접 확인하는 별도 테스트는 추가하지 않았다.

**코드 근거:** [Order](../src/main/java/com/stock_spoon/river_be/order/Order.java), AiOrderReport,
OrderService, [DB 이관 SQL](../db/ai_order_reports.sql).

## 6. 상세 조회는 사용자 권한을 먼저 검사하도록 구현했다

조회 순서는 다음과 같다.

1. 기존 SecurityConfig가 access_token 쿠키와 JWT를 검증한다.
2. AiReportController가 JWT sub에서 사용자 ID를 읽는다.
3. AiReportService가 account_id, user_id, active=true로 계좌를 찾는다.
4. order_id와 account_id를 함께 사용해 주문을 찾는다.
5. AI 출처와 리포트 존재를 검사한다.
6. 주문·리포트·체결을 읽기 전용 트랜잭션에서 응답으로 구성한다.

**결정 근거:** 리포트는 로그인한 사용자가 확인하는 화면이다. 주문 생성의 AI 전용 권한을
상세 조회에 그대로 적용하면 일반 사용자에게 리포트를 보여줄 수 없다. 생성의 AI 전용 검사와
조회의 계좌 소유권 검사를 구분했다. 다른 계좌의 order_id를 넣어도 조회되지 않도록
계좌 범위가 포함된 기존 Repository 조회를 재사용했다.

| 상황 | HTTP / 코드 |
|---|---|
| 로그인 없음 | 401 / UNAUTHORIZED |
| 숫자로 해석할 수 없는 사용자 sub | 401 / INVALID_TOKEN |
| 타인·없는·비활성 계좌 | 403 / FORBIDDEN_ACCOUNT |
| 계좌 내 주문 없음·리포트 없음·USER 주문 | 404 / AI_REPORT_NOT_FOUND |
| 정상 조회 | 200 |

**코드 근거:** [AiReportController](../src/main/java/com/stock_spoon/river_be/order/AiReportController.java),
[AiReportService](../src/main/java/com/stock_spoon/river_be/order/AiReportService.java),
[SecurityConfig](../src/main/java/com/stock_spoon/river_be/config/SecurityConfig.java),
[OrderRepository](../src/main/java/com/stock_spoon/river_be/order/OrderRepository.java).

## 7. 실행 결과는 체결 데이터에서 집계했다

기존 ExecutionRepository.findForOrder로 체결 행을 조회하고 AiReportResponse에서 집계했다.
실행 결과를 리포트에 중복 저장하지 않았다. 주문 접수 후 체결 상태가 바뀌어도 조회 시점의
체결 데이터를 사용할 수 있다. 현재 체결 엔진은 전량 체결 방식이지만 조회 집계는 여러 행도 처리한다.

```text
총 체결수량 Q = Σ 체결수량
총 거래금액 A = Σ (체결가격 × 체결수량)
평균 체결가격 = A / Q
체결 시각 = 체결 행 중 가장 늦은 시각
체결 건수 = 체결 행 개수

매도 실현손익 P = Σ 저장된 실현손익
매도분 취득원가 C = A − P
평균 매수가 = C / Q
실현수익률(%) = P / C × 100
```

**결정 근거:** 체결 가격이 서로 다르면 단순 평균보다 수량 가중 평균이 필요하다.
체결별 수익률 역시 단순 합계나 평균이 전체 거래 수익률을 보장하지 않으므로,
실현손익과 매도분 원가를 집계해 수익률을 다시 계산했다.
가격·금액·비율 계산에는 BigDecimal을 사용하고 나눗셈은 마지막에 수행한다.
평균 가격과 수익률은 소수점 4자리 HALF_UP으로 반올림한다.

평균 매수가는 저장된 실현손익을 통해 역산한다. 실현손익은 소수점 2자리로 저장되므로
원가 복원도 그 정밀도에 한정된다. 매도 체결의 실현손익이 하나라도 없으면 sell_analysis는 null이다.
매수도 sell_analysis=null이며, 체결이 없으면 execution=null이다.
원가가 양수가 아니면 실현수익률을 0으로 꾸미지 않고 null로 반환한다.

테스트 예시는 196,000원×1주와 197,000원×1주, 실현손익 9,400원과 10,400원이다.
총 거래금액 393,000원, 평균 체결가 196,500원, 실현손익 19,800원,
평균 매수가 186,600원, 실현수익률 5.3055%를 확인했다.

**코드 근거:** [AiReportResponse](../src/main/java/com/stock_spoon/river_be/order/AiReportResponse.java),
[Execution](../src/main/java/com/stock_spoon/river_be/order/Execution.java),
[ExecutionRepository](../src/main/java/com/stock_spoon/river_be/order/ExecutionRepository.java).

## 8. 데이터가 없는 화면 항목은 응답에 임의로 채우지 않았다

| 항목 | 처리 | 근거 |
|---|---|---|
| 판단 텍스트 | summary로 원문 반환 | AI가 공급하는 단일 텍스트 |
| 주문 시각 | created_at | 실제 저장된 시각. AI 판단 시각으로 바꿔 부르지 않음 |
| report_status | completed | 원문 저장 완료를 의미. 체결 완료와 구분 |
| 주문 상태 | order_status | 기존 주문의 pending/executed/cancelled |
| 종목명 | stock_name=null | 현재 종목명 저장·조회 경로가 없음 |
| 거래 후 비중 | 제외 | 거래 당시 자산 평가 스냅샷이 없음 |
| 보유 기간 | 제외 | 추가 매수·부분 매도의 기간 산정 정책이 없음 |
| 목표 도달·손실 제한 발동 | 제외 | 당시 목표·실제 판단 조건이 저장되지 않음 |
| 판단 흐름·보유 변화·예상과 결과 | 제외 | 사용자가 지정한 v1 제외 범위 |

매수 체결 결과는 공통 execution으로 제공하므로 별도 buy_analysis는 추가하지 않았다.
날짜는 기존 응답과 같이 UTC Z 형식을 사용한다. FE는 화면 표시 시 한국 시각으로 변환한다.
최초 구현에서는 report_id 등 식별 필드를 유지했지만, 이후 최신 문서와 사용자 요청에 따라 최상위 report_id는 응답에서 제거했다. DB PK는 유지한다. 최초 decided_at 및 상세 분석 구조는
현재 제공 가능한 데이터에 맞춰 줄였다. 시트 자체를 수정한 작업은 아니다.

## 9. 기존 근거 데이터를 보존하는 이관 SQL을 작성했다

db/ai_order_reports.sql은 새 테이블 생성 후 AI 주문의 비어 있지 않은 decision_summary를
reason으로 복사한다. 이전 decision_id는 새 문자열 계약에서 사용하지 않는다.
이전 주문 컬럼은 복구용으로 남겨 두되 새 애플리케이션은 읽거나 쓰지 않는다.
근거 없는 과거 주문은 리포트가 없으므로 상세 조회가 404다.

**결정 근거:** 새 테이블만 만들면 과거 주문의 리포트가 조회되지 않으므로 요약을 이관한다.
기존 컬럼을 즉시 삭제하면 복구와 비교가 어려워 삭제를 이번 작업에 포함하지 않았다.
기본 JPA 설정은 validate이므로 애플리케이션 시작만으로 이관이 실행되지는 않는다.

배포 시 적용 순서는 다음과 같다.

1. 구버전 애플리케이션의 주문 쓰기를 중단한다.
2. 대상 DB를 확인·백업한 뒤 MySQL 8.4용 이관 SQL을 한 번 적용한다.
3. 테이블 및 이관 결과를 확인한다.
4. 새 버전을 배포하고 문자열 주문 생성·상세 조회를 확인한다.

SQL은 재실행용으로 작성하지 않았다. 구버전 쓰기를 이관 중 계속 허용하면 복사 이후 생성된
주문의 근거가 새 테이블에 누락될 수 있다. 구버전으로 되돌릴 때도 새 주문의 근거는
새 테이블에만 있으므로 자동으로 구버전 컬럼에 복원된다고 가정하면 안 된다.

**실제 수행 범위:** SQL 파일 작성과 검토까지 완료했다. 운영 DB 적용 및 실제 MySQL 실행 검증,
배포 후 연동 확인은 하지 않았다. H2 JPA 스키마 테스트가 MySQL 이관 검증을 대신하지 않는다.

**코드 근거:** [ai_order_reports.sql](../db/ai_order_reports.sql),
[application.yaml](../src/main/resources/application.yaml).

## 10. 전체 테스트·빌드·정적 분석을 확인했다

호출부 변경 후 테스트 컴파일에서 AiUserSnapshotServiceTests 두 곳에 이전 decision_id 인자가
남아 있는 것을 발견했다. 컴파일 오류가 가리킨 호출과 새 시그니처를 비교해 누락 인자 변경을
완료한 뒤 전체 테스트를 다시 실행했다.

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat build --no-daemon
```

최종 구현 빌드는 BUILD SUCCESSFUL이었다. 전체 테스트 XML 집계는 총 136건,
실행·통과 131건, 건너뜀 5건, 실패 0건, 오류 0건이었다.
build의 spotbugsMain도 통과했다. 이는 2026-10-01 구현 완료 시 실행한 검증 결과이며,
이 문서 작성 때문에 제품 코드를 변경하거나 테스트를 새로 실행한 것은 아니다.

| 테스트 | 확인한 동작 |
|---|---|
| acceptsLimitOrderWithReasonAndEachRequestCreatesAnOrder | 문자열 접수, 요청마다 주문 생성, 리포트 원문 연결 |
| acceptsLongTextAndRejectsInvalidReasonWithoutSaving | 2,000자 저장·재조회, null/공백/객체/100,001자 거절, 추가 주문·리포트 없음 |
| databaseRejectsSecondReportForSameOrder | DB가 같은 주문의 두 번째 리포트 저장 거절 |
| readsOwnedReportAndAggregatesSellExecutions | 일반 사용자 조회, 2건 체결 집계, 손익 계산, 타인 403·미인증 401 |
| pendingReportHasNoExecutionAndMissingReportReturns404 | 긴 원문 조회, 대기 상태·execution=null, 없는 주문 404 |
| 기존 주문·체결·계좌·AI 스냅샷 테스트 | 이전 근거 인자 제거 후 기존 기능 회귀 확인 |

건너뛴 테스트는 키움 실연동 테스트 5건이다.

- KiwoomMarketLiveTests: retrievesSamsungCurrentPrice, checksStockInfoMarketAndStatusFields, retrievesKospiIndex
- KiwoomStockStreamLiveTests: authenticatesAndSubscribesSamsung
- KiwoomTokenLiveTests: issuesAndReusesProductionToken

독립 코드 리뷰에서는 트랜잭션 연결, 계좌 권한, 집계 계산, 이관 구조를 확인했고 주요 결함을
보고하지 않았다. 테스트가 모든 오류 분기를 개별 검증한 것은 아니다. USER 주문·리포트 없는
주문·비활성 계좌·손익 누락 등은 코드 분기가 있으나 이번 추가 테스트에서 각각 직접 검증하지 않았다.

**검증 자료:** [테스트 소스](../src/test/java/com/stock_spoon/river_be/order/OrderControllerTests.java),
로컬 `build/test-results/test/TEST-*.xml`, `build/reports/tests/test/index.html`,
`build/reports/spotbugs/main.html`. build 산출물은 이후 빌드에서 갱신될 수 있다.

## 11. 관련 문서를 현재 계약으로 정리했다

- stockspoon_order_create.md: reason 문자열 및 새 저장 위치를 반영했다.
- stockspoon_ai_report.md: 상세 응답, 권한, 집계 계산, 제외 항목, 이관 절차를 기록했다.
- 이전 stockspoon_order_create_flow.md와 stockspoon_order_phase1.md: 과거 decision_id/summary
  설명이 최신 계약으로 오인되지 않도록 상단에 변경 안내와 최신 문서 링크를 추가했다.

이번 결과물은 소스·테스트·이관 SQL·문서다. 운영 DB 적용, 배포, 공유 시트 수정,
AI/FE 실서버 통합 검증은 수행하지 않았다.
