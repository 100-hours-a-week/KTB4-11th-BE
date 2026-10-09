# v2 발견 페이지 종목 랭킹 API 설계

정리일: 2026-10-07
최종 수정일: 2026-10-08

이 문서는 대화에서 합의한 설계 기준이다. 실제 구현 또는 API 문서 반영 완료를 의미하지 않는다. FE API 경로·필드명과 아래 페이지 규칙은 현재 채택한 초안이다.

## 1. 화면 및 제공 범위

발견 페이지에서 거래대금·거래량·급상승·급하락·인기 탭을 선택한다. 종목 카드에는 전체 순위, 종목명, 업종, 가격, 등락률, 로고, 사용자 관심 여부를 표시한다.

‘더 보기’ 펼치기 방식은 제거하고 페이지 번호 기반 페이지네이션을 적용한다. 페이지당 기본 20개, 허용 개수 1~20을 기준으로 한다.

| 탭 | FE type | 최대 제공 종목 수 | size=20일 때 최대 페이지 |
|---|---|---:|---:|
| 거래대금 | TRADING_VALUE | 100 | 5 |
| 거래량 | VOLUME | 100 | 5 |
| 급상승 | RISE | 100 | 5 |
| 급하락 | FALL | 100 | 5 |
| 인기 | POPULAR | 20 | 1 |

실제 반환된 종목이 상한보다 적으면 실제 개수를 제공한다. 급상승·급하락은 전일 대비 등락률 순위이며 ka10019는 사용하지 않는다.

## 2. 수집과 조회 구조

BE → 키움 수집 요청과 FE → BE 화면 조회 요청을 분리한다.

1. BE가 키움 REST API로 다섯 탭의 첫 페이지를 수집한다.
2. 탭별 순위·가격을 공통 형태로 변환해 최신 캐시에 보관한다.
3. FE는 현재 선택한 탭과 페이지만 BE에 요청한다.
4. BE는 캐시를 조회하고 사용자별 관심 여부를 합쳐 반환한다.

정상적인 FE 조회 및 페이지 변경마다 키움 API를 호출하지 않는다. 수집 재개 시에는 선택한 탭을 우선 갱신한 뒤 반환하고 나머지 탭을 이어서 수집하는 기준으로 한다. 동시 진입으로 수집 작업이 중복 시작되지 않도록 관리한다.

### 갱신 및 30초 유예

| 구분 | 규칙 |
|---|---|
| BE 수집 | 장중 각 탭을 5초마다 갱신 |
| FE 조회 | 발견 탭이 활성화되고 화면이 보이는 동안 현재 탭·페이지를 5초마다 조회 |
| 첫 진입·탭 변경·페이지 변경·화면 복귀 | 즉시 조회 |
| 탭 변경 | page=1로 초기화 |
| 탭 이탈·앱 백그라운드 | FE 주기적 조회 중단 |
| 마지막 FE 조회 이후 30초 | 추가 조회가 없으면 BE 순위 수집 중단 |
| 재진입 | 수집 재개 |

로그인 또는 홈 화면 진입만으로 발견 순위 수집을 시작하지 않는다. 여러 사용자 중 누군가 발견 순위를 계속 조회하면 공통 수집을 유지한다. FE에서 이전 요청이 끝나지 않았으면 다음 요청을 겹쳐 보내지 않는다.

BE와 FE의 갱신 타이밍이 독립적이므로 변화가 화면에 반영되기까지 약 10초에 통신·처리 시간이 더해질 수 있다. 조회 실패 시 기존 캐시를 유지하며 마지막 성공 갱신 시각을 반환한다. 장중의 정확한 시간 범위·휴장일 판정은 구현 시 정해야 한다.

### 호출 분산 및 제한

다섯 요청을 동시에 실행하지 않고 예를 들어 0초 거래대금, 1초 거래량, 2초 급상승, 3초 급하락, 4초 인기, 5초 거래대금 순으로 분산한다. 각 탭은 5초 주기를 유지한다.

국내주식 조회 한도는 계좌별(토큰별) 모든 조회를 합쳐 초당 5회다. 주문 초당 5회는 별도로 안내된다. 순위·지수·호가·첫 진입 수집·재시도·연속조회가 공통 조회 제한을 거치도록 한다. 서버가 여러 대여도 같은 토큰의 수집이 중복되지 않도록 한다.

첫 페이지 5회/5초 기준 평균 초당 1회, 시간당 3,600회다. 09:00~15:30의 6.5시간 내내 활성 상태라면 23,400회이며 재시도 등은 제외한 계산이다. 공개 이용안내에는 국내주식 일일·시간당 한도가 별도로 명시되지 않았으나 추가 제한이 전혀 없음을 보장하는 것은 아니다.

## 3. BE → 키움 API

모든 요청은 POST이며 공통 헤더는 다음과 같다.

```http
Content-Type: application/json;charset=UTF-8
authorization: Bearer {키움 접근토큰}
api-id: {API ID}
```

다음 요청값은 전체 시장·KRX·관리종목 제외 기준이다. 인기 집계는 기존 테스트와 문서 초안의 1분 기준(qry_tp=1)을 사용한다.

### 거래대금

- API ID: `ka10032`
- URL: `https://api.kiwoom.com/api/dostk/rkinfo`
- 응답 목록: `trde_prica_upper`

```json
{"mrkt_tp":"000","mang_stk_incls":"0","stex_tp":"1"}
```

필요 필드: `now_rank`(현재 순위), `stk_cd`(종목코드), `stk_nm`(종목명), `cur_prc`(현재가), `flu_rt`(전일 대비 등락률), `pred_pre_sig`(전일 대비 기호), `trde_prica`(거래대금, 백만원).

### 거래량

- API ID: `ka10030`
- URL: `https://api.kiwoom.com/api/dostk/rkinfo`
- 응답 목록: `tdy_trde_qty_upper`

```json
{"mrkt_tp":"000","sort_tp":"1","mang_stk_incls":"1","crd_tp":"0","trde_qty_tp":"0","pric_tp":"0","trde_prica_tp":"0","mrkt_open_tp":"0","stex_tp":"1"}
```

필요 필드: `stk_cd`, `stk_nm`, `cur_prc`, `flu_rt`, `pred_pre_sig`, `trde_qty`(거래량, 주). 순위는 응답 순서에 따라 BE에서 부여한다.

관리종목 제외 값은 ka10032의 mang_stk_incls=0, ka10030의 mang_stk_incls=1로 서로 다르다.

### 급상승·급하락

- API ID: 두 탭 모두 `ka10027`
- URL: `https://api.kiwoom.com/api/dostk/rkinfo`
- 응답 목록: `pred_pre_flu_rt_upper`

급상승 요청:

```json
{"mrkt_tp":"000","sort_tp":"1","trde_qty_cnd":"0000","stk_cnd":"1","crd_cnd":"0","updown_incls":"1","pric_cnd":"0","trde_prica_cnd":"0","stex_tp":"1"}
```

급하락은 위 바디의 `sort_tp`만 `"3"`으로 변경한다. `updown_incls=1`은 상한가·하한가 포함이다.

필요 필드: `stk_cd`, `stk_nm`, `cur_prc`, `flu_rt`, `pred_pre_sig`. 순위는 응답 순서에 따라 BE에서 부여하고 상위 100개까지만 FE에 제공한다.

### 인기

- API ID: `ka00198`
- URL: `https://api.kiwoom.com/api/dostk/stkinfo`
- 응답 목록: `item_inq_rank`

```json
{"qry_tp":"1"}
```

| 필드 | 의미 |
|---|---|
| bigd_rank | 인기 순위 |
| stk_cd / stk_nm | 종목코드 / 종목명 |
| past_curr_prc | 기준 시점 가격 |
| base_comp_chgr | 기준가 대비 등락률 |
| base_comp_sign | 기준가 대비 기호 |
| dt / tm | 기준 일자 YYYYMMDD / 시간 HHmmss |

qry_tp는 1=1분, 2=10분, 3=1시간, 4=당일 누적, 5=30초이며 집계 구간을 뜻한다. 화면 갱신 주기와는 별개다. 이 API에는 시장·거래소 선택 필드가 없다.

### 응답 처리와 별도 데이터

- 성공 여부는 `return_code`, `return_msg`로 확인한다. 실제 다섯 요청 테스트는 모두 return_code=0으로 성공했다. 외부 키움 응답의 결과 필드 표기 차이는 실제 연동 시 확인한다.
- 원본 숫자 필드는 문자열이다. 가격의 방향 부호는 제거하고 등락률의 부호는 유지한다. `1.5`는 1.5%이지 0.015가 아니다.
- pred_pre_sig와 base_comp_sign은 1=상한가, 2=상승, 3=보합, 4=하한가, 5=하락이다.
- 인기의 기준 시점 가격·기준가 대비 등락률을 다른 탭의 최신 현재가·전일 대비 등락률과 동일하게 취급하지 않는다.
- 업종·로고는 별도 종목 데이터, 관심 여부는 사용자별 서비스 데이터에서 결합한다. 업종 데이터 출처와 화면 분류의 대응은 별도 확인이 필요하다.
- 현 구성에서는 전체 순위 종목의 가격을 WebSocket으로 구독하지 않는다. 공식 실시간 시세 한도는 세션당 200종목이며 기존 기능과 공유해야 한다.

### 실제 1회 조회 테스트

2026-10-07, 전체 시장·KRX 기준(인기는 1분 집계)으로 각 API를 한 번씩 호출했다.

| 탭 | 반환 종목 수 | 응답 cont-yn |
|---|---:|---|
| 거래대금 | 100 | Y |
| 거래량 | 100 | Y |
| 급상승 | 200 | Y |
| 급하락 | 200 | Y |
| 인기 | 20 | N |

이는 이번 호출의 관측값이며 공식 최대 반환 개수로 확정한 값은 아니다. 인기 1분 집계는 이번 응답에서 후속 페이지가 없었다. 다른 집계 구간은 테스트하지 않았다.

현재 수집은 첫 페이지만 사용한다. 키움의 cont-yn/next-key는 추가 결과 조회용이며 자동 실시간 수신이나 FE 페이지 번호를 의미하지 않는다.

## 4. FE → BE API 초안

FE 요청·응답 필드명은 스네이크 케이스(snake_case)로 통일한다. 순위 기준의 열거형 값(TRADING_VALUE 등)은 기존 대문자 표기를 유지한다.

| 항목 | 내용 |
|---|---|
| API명 | 발견 탭 종목 순위 조회 |
| Method | GET |
| URL | /api/v2/stocks/rankings |
| Body | 없음 |
| 처리 | 현재 캐시 조회 + 사용자별 관심 여부 |

| Query | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| type | String | Y | 없음 | TRADING_VALUE / VOLUME / RISE / FALL / POPULAR |
| page | Integer | N | 1 | 1부터 시작 |
| size | Integer | N | 20 | 허용 범위 1~20 |

```http
GET /api/v2/stocks/rankings?type=TRADING_VALUE&page=1&size=20
```

위 요청은 거래대금 1~20위를 조회한다. page=2이면 21~40위를 조회한다.

### 응답 예시

다음 값은 설명용이며 실제 시세가 아니다. items는 예시로 2개만 표시했으며 실제 응답에는 최대 size개가 포함된다. 아래 조건(total_elements=100, page=1, size=20)의 실제 응답에는 1~20위 20개가 들어간다. 생략 문구는 JSON 안에 넣지 않는다.

```json
{
  "ranking_type": "TRADING_VALUE",
  "updated_at": "2026-10-08T10:00:00+09:00",
  "page": 1,
  "size": 20,
  "total_elements": 100,
  "total_pages": 5,
  "has_previous": false,
  "has_next": true,
  "items": [
    {
      "rank": 1,
      "stock_code": "005930",
      "stock_name": "삼성전자",
      "sector_name": "전기,전자",
      "logo_url": null,
      "price": 74100,
      "change_rate": 1.5,
      "is_favorite": false
    },
    {
      "rank": 2,
      "stock_code": "000660",
      "stock_name": "SK하이닉스",
      "sector_name": "전기,전자",
      "logo_url": null,
      "price": 183000,
      "change_rate": 2.6,
      "is_favorite": true
    }
  ]
}
```

| Response 필드 | 타입 | 의미 |
|---|---|---|
| ranking_type | String | 순위 기준 |
| updated_at | String | BE가 해당 탭의 순위·가격을 마지막으로 성공적으로 수집한 시각. 시간대가 포함된 ISO 8601 |
| page / size | Integer | 현재 페이지 / 요청한 페이지당 개수 |
| total_elements | Integer | 제공 상한을 적용한 실제 종목 수 |
| total_pages | Integer | 전체 페이지 수 |
| has_previous / has_next | Boolean | 이전 / 다음 페이지 존재 여부 |
| items[].rank | Integer | 전체 목록 기준 순위 |
| items[].stock_code / stock_name | String | 종목코드 / 종목명 |
| items[].sector_name / logo_url | String 또는 null | 업종 / 로고 |
| items[].price | Number 또는 null | 부호를 제거한 가격, 원 |
| items[].change_rate | Number 또는 null | 부호를 유지한 등락률, % |
| items[].is_favorite | Boolean | 로그인 사용자의 관심 여부 |

### 가격 및 갱신 시각의 의미

- 가격 기준을 구분하는 필드는 응답에서 생략한다. 일반 탭의 price는 마지막 REST 조회에서 받은 현재가이고 change_rate는 전일 대비 등락률이다.
- 인기 탭의 price는 기준 시점 가격이며 change_rate는 기준가 대비 등락률이다. FE는 이를 전일 대비 등락률로 단정하지 않는다.
- 시간 필드는 최상위 updated_at 하나로 통일한다. 종목별 시간 필드는 제공하지 않는다.
- updated_at은 모든 탭에서 BE 수집 성공 시각을 뜻한다. 원본 시세 발생 시각이나 FE 요청 시각이 아니다.
- FE에 캐시를 반환할 때마다 updated_at을 바꾸지 않는다. 키움 조회에 실패해 기존 캐시를 반환하면 마지막 성공 시각을 그대로 유지한다.
- 인기 원본의 dt/tm은 순위 데이터의 기준 시각으로 updated_at과 의미가 다르다. 현재 FE 응답에는 별도로 제공하지 않는다.
- 시간 형식은 시간대가 포함된 ISO 8601(예: 2026-10-08T10:00:00+09:00)을 사용한다.
- sector_name의 ‘전기,전자’는 별도 종목 데이터의 업종명을 표시한 예시다. 화면 전용 ‘반도체’ 등의 분류를 사용할지는 별도 결정 사항이다.
- is_favorite는 요청 사용자별로 결합하며 공통 순위 캐시에 사용자별 관심 여부를 저장하지 않는다.

### 페이지 처리 규칙

- total_pages는 ceil(total_elements / size)다.
- 전체 순위 번호는 페이지마다 1부터 재시작하지 않는다.
- 매 요청은 최신 캐시를 읽는다. 5초 갱신으로 순위가 바뀌면 페이지 구성도 바뀔 수 있으며 여러 페이지에 걸친 동일 스냅샷을 보장하지 않는다.
- 잘못된 type, page<1, size 범위 초과는 400으로 처리한다.
- 전체 페이지를 초과하면 200과 빈 items를 반환한다. has_previous/has_next는 실제 존재하는 인접 페이지를 기준으로 한다.
- FE 페이지네이션은 BE 캐시를 나누어 반환하며 키움 연속조회를 발생시키지 않는다.
- 기존 next_cursor 제안은 페이지 번호 방식으로 대체한다.

## 5. 구현 시 남은 상세 결정

- 장중 시간·휴장일 정책과 장외 첫 진입 시 반환 정책.
- 인증 및 공통 오류 응답 형식, 초기 수집 실패 시 응답.
- 업종·로고 데이터 출처와 캐시 저장 수단.
- 수집 시작·중단, 호출 제한을 여러 BE 인스턴스에서 공유하는 방법.

## 참고 자료

- [프로젝트 API 문서](https://docs.google.com/spreadsheets/d/1x2RqgSykrsg1HIUVcOl_iW0Jb47wEJDRtrIyESRVHeE/edit?gid=2138787152#gid=2138787152)
- [키움 공식 API 명세](https://github.com/Kiwoom-Securities/Kiwoom-REST-API/blob/main/kiwoom/_data/kiwoom_api_spec.json)
- [키움 공식 이용안내 및 호출 제한](https://openapi.kiwoom.com/intro?dummyVal=0)
- [키움 공식 REST / WebSocket 튜토리얼](https://openapi.kiwoom.com/m/guide/index?dummyVal=0)

