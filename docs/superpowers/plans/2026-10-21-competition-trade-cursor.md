# 대결 매매 내역 cursor 페이지네이션 계획

**Goal:** 기존 GET /api/v2/accounts/{accountId}/executions에 선택적 cursor 페이지네이션을 적용한다.

**Architecture:** 주문별 MAX(체결 시각) DESC, 주문 ID DESC로 주문을 먼저 선택한다. 그 주문의 체결 행 전체를 읽고 기존 서비스의 합산 로직을 적용한다. 체결 행을 잘라 주문 합산이 불완전해지지 않도록 한다.

**Tech Stack:** 기존 Spring MVC·Spring Data JPA·Jackson·Lombok, 기존 HTTP 통합 테스트.

## 범위·계약

- 작업 시작 시 현재 브랜치는 dev. 사용자 허가 없이 생성·전환·커밋·푸시하지 않는다.
- cursor·limit 모두 생략하면 기존 전체 조회와 응답을 유지한다.
- limit은 1~100. cursor만 있으면 기본 10. 전체 조회에는 pagination을 생략한다.
- 분할 응답에는 pagination(limit, has_next, next_cursor)을 추가한다. 마지막 커서는 null이다.
- 커서는 주문별 최신 체결 시각·주문 ID·계좌 ID를 담는다. 잘못된 형식·다른 계좌의 커서는 400이다.
- 매수/매도 필터는 별도 후속 작업이며 이번에는 추가하지 않는다.
- 체결 완료 주문만 반환하고 본인 활성 대결 계좌·완료 전 AI 내역 제한을 유지한다.
- order 패키지의 서비스·DTO를 재사용하지 않는다. 기존 Entity 관계와 AccountRepository는 유지한다.
- 시각이 같은 주문은 ID로 구분한다. 신규 체결은 첫 페이지 재조회로 확인한다.

## 파일과 실행 단계

- [x] CompetitionTradeHistoryTests에 전체/분할/빈 결과/동일 시각/새 체결/계좌 격리/잘못된 입력/접근 제한 테스트를 작성하고 실패를 확인한다.
- [x] dto/CompetitionTradeHistoryCursor.java를 추가해 URL-safe 커서 인코딩·검증과 시각 정밀도 보존을 처리한다. 전용 단위 테스트를 추가한다.
- [x] CompetitionTradeHistoryResponse에 선택적 Pagination을 추가한다.
- [x] CompetitionTradeHistoryRepository에 주문별 정렬 키 조회(limit+1 또는 unpaged)와 선택 주문의 체결 조회를 작성한다. HAVING은 집계된 체결 시각과 ID로 다음 범위를 제한한다.
- [x] CompetitionTradeHistoryController.list(jwt, accountId, cursor, limit)와 Service.list(...)를 연결한다. 실제 응답 주문만 합산·종목 조회한다.
- [x] 주문·체결 인덱스 구성을 확인한다. 실제 실행 계획 없이 추가 인덱스를 무조건 작성하지 않는다. 운영 DB는 변경하지 않는다.
- [x] 관련 회귀 테스트와 spotbugsMain을 수행한다. 문서에 실제 메서드·인자 전달·분기와 예외를 반영한다.

## 검증

테스트에서는 종목 정보 호출만 대체하고 실제 HTTP와 H2로 쿼리·권한·응답을 확인한다. 기존 두 체결 행 합산 fixture를 페이지 경계에도 사용한다. 운영 MySQL 실행 계획과 부하 검증은 별도다.

## 실행 결과

관련 테스트 57개(매매 내역 HTTP 9개, 매매 커서 10개, 기존 목록·상세·결과 포함)와 spotbugsMain이 통과했다. 기존 인덱스만 확인했고 운영 MySQL 실행 계획·부하 검증과 DB 변경은 하지 않았다. 현재 브랜치 dev를 유지했으며 커밋·푸시는 하지 않았다. 매수·매도 필터는 후속 별도 작업이다.
