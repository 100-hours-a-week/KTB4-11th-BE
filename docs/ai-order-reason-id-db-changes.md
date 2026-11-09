# AI 주문 reason_id 수신 및 운영 DB 변경

## 현재 구현 범위

- OrderCreateRequest.reasonId를 reason_id JSON 필드에 연결했다.
- null은 허용하며, 값이 있으면 8-4-4-4-12 형태의 UUID 문자열인지 Bean Validation으로 검사한다.
- 값이 없으면 직렬화에서 생략한다. 기존 DTO 직렬화로 계산하던 주문 payloadHash의 입력에 reason_id:null이 추가되는 것을 방지한다.
- 기존 Java 생성자 호출부는 reasonId=null로 유지한다.
- 지정가·시장가 서비스에서 reasonId를 Order 생성 메서드와 AiOrderReport 생성자로 전달한다.
- AiOrderReport.reasonId는 reason_id VARCHAR(36) NULL에 매핑하며 최초 저장 후 수정하지 않는다. AI가 보낸 문자열은 대소문자 변환 없이 보존한다.
- 과거 요청은 null을 유지한다. 사용자 주문은 reasonId를 리포트에 저장하지 않는다.

## 저장 호출 흐름

```text
OrderRequestListener.onMessage(message)
 → OrderRequestCodec.decode(message.body(), sentAt)
   → OrderCreateRequest.reasonId에 payload.reason_id 수신
 → OrderRequestService.consume(m)
   → OrderRequestTransactions.validate(m) 및 기존 시세 확인
   → OrderRequestTransactions.process(m, quote, prices, validateQuote)
     → 기존 처리 이력 확인, 요청 검증, 계좌·자원 검사
     → 지정가: OrderService.reserveLimit(userId, accountId, request, source)
       → private reserveLimit(..., reasonId, source)
       → Order.pendingLimit(..., reasonId, now)
     → 시장가: OrderExecutionService.executeMarket(userId, accountId, request, source, book, prices, validate)
       → private executeMarket(..., reasonId, prices, validate, source)
       → Order.pendingMarket(..., reasonId, now)
     → new AiOrderReport(..., reasonId)
     → OrderRepository.save(order): cascade로 리포트도 저장
     → 처리 이력 저장 후 트랜잭션 커밋
```

AI 주문 메시지가 도착하면 order.messaging 패키지의 OrderRequestListener.onMessage()가 메시지를 받고, 본문과 SQS 전송 시각을 OrderRequestCodec.decode()에 넘긴다. decode()는 payload의 reason_id를 DTO의 reasonId에 보관한다. OrderRequestService.consume()는 기존 검증 및 시장가 시세 확인 경로를 거쳐 OrderRequestTransactions.process()에 주문 메시지를 전달한다.

process()는 계좌를 잠그고 중복 요청과 주문 조건을 확인한 뒤, 지정가는 OrderService.reserveLimit(), 시장가는 OrderExecutionService.executeMarket()을 호출한다. 각 서비스는 AI 요청일 때 request.reasonId()를 꺼내 내부 메서드와 Order의 생성 메서드에 전달한다. Order 생성 중 만들어지는 AiOrderReport가 UUID를 원문 그대로 저장한다. 기존 Order.report의 CascadeType.ALL 설정에 의해 주문 저장 시 리포트도 저장되므로 리포트 전용 save 호출을 추가하지 않았다.

원래 UUID가 없던 Java 호출은 기존 생성자·팩토리 오버로드에서 null을 전달한다. 엔티티 생성자도 잘못된 UUID를 거절한다. 저장 중 실패하면 기존 주문 처리 트랜잭션의 주문·리포트·자산·처리 이력을 함께 롤백한다. 지정가 주문은 대기 상태에서도 리포트를 저장하며 체결을 기다리지 않는다.

## 운영 DB에 필요한 변경 — 별도 적용

대상 테이블은 trade_orders가 아니라 ai_order_reports다.

| 추가 컬럼 | 타입 | NULL | 의미 |
|---|---|---|---|
| reason_id | VARCHAR(36) | 허용 | AI가 보낸 판단 근거 UUID 원문 |

실행 SQL: [ai_report_reason_id.sql](../db/ai_report_reason_id.sql)

```sql
ALTER TABLE ai_order_reports
    ADD COLUMN reason_id VARCHAR(36) NULL;
```

기존 report_id·order_id와 데이터는 유지한다. 기존 행은 NULL로 남는다. 이미 받지 못한 UUID를 임의 생성하거나 report_id에서 추론해 채우지 않는다. 동일 판단 근거를 여러 주문에서 공유할 가능성은 확정되지 않아 UNIQUE 제약은 추가하지 않는다.

## 적용 순서

1. 실제 연결 대상 DB가 맞는지 확인하고 아래 읽기 쿼리로 컬럼 존재 여부를 확인한다.
2. reason_id가 없을 때만 위 ALTER를 한 번 적용한다. 이미 있으면 정의를 비교하고 중복 실행하지 않는다.
3. 위 ALTER가 완료된 DB에 엔티티 매핑·시장가/지정가 저장 변경을 배포한다. 매핑이 추가되어 컬럼이 없는 DB에서는 리포트 조회·저장에도 오류가 발생할 수 있으므로 DB 변경이 먼저다.
4. reason_id를 포함한 새 AI 주문이 처리되면 주문과 연결해 원래 UUID가 저장됐는지 확인한다.

```sql
SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_order_reports'
  AND COLUMN_NAME = 'reason_id';

-- 컬럼 및 저장 로직 적용 후 확인
SELECT report_id, order_id, reason_id
FROM ai_order_reports
WHERE reason_id IS NOT NULL
ORDER BY report_id DESC
LIMIT 10;
```

이번 작업에서는 운영 DB에 접속하거나 DDL을 실행하지 않았다. 운영 DB의 기존 정의, 테이블 크기와 ALTER 수행 시 잠금·실행 시간도 확인하지 않았다. 배포를 되돌려도 nullable 추가 컬럼은 남겨둘 수 있으므로, 저장된 UUID를 삭제하는 DROP COLUMN은 기본 롤백 절차에 넣지 않는다.

## 후속 작업

- 동일 eventId 재전송 시 reasonId 변경도 다른 요청으로 감지되는지 검증.
- 대결 리포트 요청에는 해당 AI 참가자 계좌의 체결 주문만 포함하도록 확정·연결했다. null을 제외하고 동일 ID를 제거해 portfolio_reason_ids를 구성한다. 자동 전송 흐름은 [Outbox 문서](competition-report-outbox.md), 운영 적용 전체 목록은 [배포 문서](competition-report-deployment.md)를 참고한다.

## 검증 범위

- SQS 형식 JSON을 decode한 뒤 실제 OrderRequestTransactions.process()를 거쳐 지정가·시장가 주문을 저장하고 JDBC로 원본 UUID를 확인한다.
- reason_id가 없는 기존 AI 주문도 두 경로에서 리포트와 NULL 값을 저장하는지 확인한다.
- 로컬 테스트는 H2로 실행하며 운영 DB 및 실제 AWS SQS 전송은 검증하지 않는다.
