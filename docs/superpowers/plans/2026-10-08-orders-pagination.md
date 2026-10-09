# 체결 주문 내역 페이지네이션 구현 계획

사용자가 대화에서 검토한 계획을 승인한 뒤 현재 `feat/98-orders-pagination` 브랜치에서 진행한다.

## 목표와 계약

- AI 체결 완료 주문에 BE 페이지네이션과 매수/매도 필터를 적용한다.
- `page`는 선택 입력이며 0부터 시작한다. 생략 시 기존 전체/limit 조회 및 응답을 유지한다.
- 페이지 조회의 limit은 기본 10, 범위 1~100. 기존 비페이지 조회의 양의 int limit 규칙은 유지한다.
- `order_side`는 생략/buy/sell. 생략하면 양쪽을 반환한다. 빈 값과 all은 오류다.
- 주문별 마지막 체결시각 내림차순, 동률은 주문 ID 내림차순이다.
- 페이지의 항목과 전체 개수는 주문 단위이며 계좌/AI/EXECUTED/side 조건을 모두 적용한다.
- 페이지 요청만 pagination(page, limit, total_elements, total_pages, has_next)을 반환한다.
- 빈 결과는 전체 페이지 수 0, 범위 초과 페이지는 빈 배열을 반환한다.
- 홈 `?limit=3`은 유지한다. 보유 종목 조회와 주문 생성/취소는 변경하지 않는다.

## 작업과 검증

- [x] OrderHistoryTests에 필터 후 페이지 선택, 동률 정렬, 복수 체결 집계, 입력 검증, 빈 페이지 테스트 작성.
- [x] 기존 구현에서 새 테스트 3개 실패 확인(총 9개 중 3개 실패).
- [x] OrderController에 page/order_side 입력 추가.
- [x] OrderRepository에 주문별 집계 정렬 조회 및 같은 조건의 count 조회 추가.
- [x] OrderHistoryService에서 소유권 확인 후 DB 페이지 조회, 선택 주문의 체결 일괄 조회.
- [x] ExecutionRepository의 일괄 체결 조회 순서를 시각/ID 오름차순으로 고정.
- [x] OrderHistoryResponse에 null일 때 생략하는 pagination 추가.
- [x] 복수 체결의 수량/금액/평균가격/마지막 시각/매도 손익 및 수익률 집계.
- [x] 수정 후 OrderHistoryTests 9개 통과 확인.
- [x] 회귀 테스트 확장, 전체 test/spotbugsMain 실행 성공, 별도 읽기 전용 코드 리뷰에서 actionable finding 없음.
- [x] 주문 내역 문서와 FE 연동 예시 갱신.

## 구현 결정

- DB 페이지 조회는 체결을 주문별로 GROUP BY한 뒤 적용한다. 단순 조인 행에 제한하지 않는다.
- 체결이 없는 EXECUTED 주문도 LEFT JOIN으로 후보에 남겨 조회 대상이면 오류로 검증한다.
- 기존 비페이지 요청은 전체 후보 검증 후 limit을 적용한다. 페이지 요청은 선택 페이지의 데이터만 검증한다.
- 평균가격은 기존 AI 리포트처럼 소수 4자리 HALF_UP인 JSON 숫자로 반환한다.
- 단일 체결의 저장 수익률은 호환성을 위해 유지한다. 복수 체결은 합산 매도금액에서 합산 손익을 뺀 원가로 수익률을 구한다.
- 매도 체결 중 손익이 누락되면 복수 체결 요약 손익/수익률은 null로 두고 잘못된 부분 합계를 표시하지 않는다.
- JPA offset의 int 한계를 고려해 범위 밖 요청은 빈 배열, 유효 범위 안이지만 offset이 int를 넘으면 400으로 처리한다.
- 현재 feature 브랜치에서 직접 수정하며 사용자 기존 수정 docs/stockspoon_account_flow.md는 보존한다.
- FE 소스가 없어 FE 화면을 수정하거나 실제 브라우저 연동을 검증하지 않는다.

## 실행 명령

프로젝트 루트에서 `./gradlew.bat test --tests '*OrderHistoryTests' --no-daemon`, 이후 `./gradlew.bat test spotbugsMain --no-daemon`.
실제 키움 및 운영 MySQL 연결은 이 자동 검증에 포함하지 않는다. 커밋/푸시/PR 생성은 이번 요청에 포함하지 않는다.
