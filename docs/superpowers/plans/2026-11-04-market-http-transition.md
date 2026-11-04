# 시세 서버 HTTP 전환

사용자 승인: 현재 `refactor/243-kiwoom-to-redis` 브랜치에서 시세 서버 HTTP API를 사용한다.

## 구현 범위

- 기존 서비스의 지연 조회, Redis 종목 메타데이터 캐시, FE 응답을 유지한다.
- 종목 조회에 `GET /market/stocks/{code}`를 사용한다. 기존 키움 조회 인터페이스와의 경계에서 `market=KOSPI`를 `marketCode=0`, `sector_name`을 `upName`으로 변환한다.
- 공급 소스가 현재 KOSPI를 수집하므로 확인되지 않은 시장은 임의 코드로 변환하지 않는다.
- HTTP 오류, 잘못된 코드/필드/시장, 빈 응답을 실패로 처리하며 키움으로 재시도하지 않는다.
- 최종 사용자 결정: 원주가가 제공되지 않으므로 시세 서버의 수정주가(`upd_stkpc_tp=1`)를 사용한다. 원주가 대비 차이와 기존 저장 결과의 기준 혼재를 운영 문서에 기록한다.

## 작업

- [x] 요청 주소/GET/무인증, 필드 매핑, 캐시 미스 및 오류 시 키움 미호출 테스트를 작성하고 실패를 확인한다.
- [x] 시세 서버 종목 클라이언트를 추가하고 운영 KiwoomMarketClient의 종목 조회 경로에 연결한다.
- [x] 조회 URL 설정과 운영 문서를 갱신한다.
- [x] 관련 테스트 및 전체 회귀 검증을 실행한다.

최종 검증: `gradlew test spotbugsMain` 성공. 테스트 695개 중 671개 통과, 조건부 24개 제외, 실패·오류 0개. 추가된 공개 서버 연결 테스트는 `MARKET_HTTP_LIVE_TEST=true`로 별도 실행해 통과했다.
2026-11-04 16:34 KST 재검증: 동일 명령 성공. 마지막 레거시 환경변수 충돌 수정이 포함된 전체 회귀 테스트를 재실행했다. 독립 재검토에서도 이전 설정 충돌이 해소된 것을 확인했다.
2026-11-04 16:38 KST 커밋 전 재검증: 전체 회귀 테스트·정적 분석 성공. 개발 PC의 실제 Java 클라이언트에서 공개 종목정보·과거 종가 조회 성공. 사용자 지시로 이번 작업은 커밋·검증까지만 진행하며 원격 푸시·배포는 수행하지 않는다.
독립 코드 검토에서 선택 실행 다중 프로세스 테스트의 기존 중계 설정과 과거 `KIWOOM_COLLECTOR_ENABLED=true`가 heartbeat/내부 API를 켤 수 있는 충돌을 발견했다. 레거시 전용 플래그로 묶고 회귀 테스트를 추가했다. 실제 Redis를 사용하는 선택 테스트와 운영 배포 연결은 실행하지 않았다.
종가도 시세 서버 HTTP 일봉으로 전환했다. 날짜 일치·페이지 조회·반복 커서·가격 정밀도·장애 시 키움 미호출 테스트 및 대회 결과·일별 성과 회귀 테스트가 통과했다. 독립 코드 검토에서 추가 결함은 발견되지 않았다.

## 검증할 위험

종목코드 불일치, 누락/잘못된 필드, 시장값 변경, 캐시 재사용, HTTP 장애 시 원치 않는 키움 접근.

## 공급 코드 근거

- `100-hours-a-week/KTB4-11th-AI`: `services/market-http/src/market_http/repositories/stocks.py`
- 시장 기본값과 수집 범위: `services/market-syncer/src/market_syncer/{database,kiwoom}.py`
- 수정주가: `services/market-collector/src/market_collector/kiwoom/official.py`의 `daily_page()`
