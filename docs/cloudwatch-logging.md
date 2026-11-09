# CloudWatch 로그 수집

애플리케이션은 기존 SLF4J/Logback으로 콘솔(stdout)에 한 줄 로그를 출력한다.
CloudWatch 직접 전송 SDK나 AWS 자격 증명을 애플리케이션에 추가하지 않는다.
ECS/Fargate는 task definition의 awslogs 설정과 실행 역할의 로그 권한이 필요하다.
현재 .github/workflows/cd.yaml은 EC2의 /opt/cloud에서 Docker Compose로 배포한다.
실제 Compose 설정은 이 저장소 밖에 있으므로 현재 수집 연결 여부는 확인하지 못했다.
EC2 Docker Compose에서는 awslogs 로그 드라이버를 사용하거나,
컨테이너 로그 파일을 수집하는 에이전트를 설정해야 한다.
이 변경은 애플리케이션 로그만 추가하며 AWS 수집 설정과 실제 전달 검증은 별도다.

## 추적

HTTP 요청마다 서버가 UUID를 만들고 응답 X-Request-Id 헤더에 반환한다.
같은 요청 스레드의 로그에는 requestId가 붙고 종료 시 이전 MDC 값을 복원한다.
스케줄러/WebSocket/비동기 작업에는 requestId=none이 표시된다.
그 작업은 event, apiId, stockCode, orderId로 추적한다.
경로는 Spring 매핑 템플릿을 기록한다. 인증 단계에서 거절되거나 매핑되지 않으면 unmapped다.
HTTP 로그 상태는 예외가 밖으로 전파되면 500, 그 외에는 실제 응답 상태다.
주문 생성·체결·사용자 취소 완료는 트랜잭션 서비스 반환 후 기록한다.

## 수준 및 보안

INFO: HTTP 결과, 로그인 결과, 업무 결과, 토큰 갱신, 웹소켓 상태 변화.
WARN/ERROR: API 거절, 외부 호출 실패, 응답 형식 오류, 백그라운드 처리 실패.
DEBUG: 외부 호출 시작/성공 및 코스피 반복 갱신.
필요 시 LOGGING_LEVEL_COM_STOCK_SPOON_RIVER_BE_MARKET=DEBUG로 한시적으로 활성화한다.
키움 httpStatus=0은 HTTP 오류 응답이 없는 통신/변환 실패를 뜻한다.
returnCode/detailCode는 숫자만 허용하며 공급자 return_msg 원문은 출력하지 않는다.
토큰, 시크릿, 쿠키, 인가코드, 사용자 프로필, 요청/응답 본문은 기록하지 않는다.
기존 accountId는 DB 내부 식별자이며 실제 증권 계좌번호가 아니다.

## CloudWatch Logs Insights 예시

```text
fields @timestamp, @message
| filter @message like /event=kiwoom_query_failed|event=kiwoom_query_rejected|event=ai_quote_rejected/
| sort @timestamp desc
| limit 100
```

requestId를 확인한 뒤 같은 값으로 검색하면 요청 전체 흐름을 확인할 수 있다.
인프라 확인: 배포 후 API 한 번 호출 → X-Request-Id 확인 → CloudWatch에서 해당 ID 검색.
토큰 발급이 아닌 일반 조회 실패 테스트로 HTTP 상태와 TR ID가 보이는지 확인한다.

## 대결 생성 실패 추적

`POST /api/v2/users/me/competitions`는 `event=competition_creation_failed`로 실패 단계·원인 코드·내부 ID·원인 유형·코드 위치를 기록한다. JSON 변환·DTO 검증 실패도 포함하며, 토큰·본문·대결명·거절된 입력값·일반 예외 메시지는 기록하지 않는다. 자세한 단계와 호출 순서는 [대결 생성 문서](competition-creation.md#생성-실패-원인-로그--fixcompetition-create-logging)를 참고한다.

```text
fields @timestamp, @message
| filter @message like /event=competition_creation_failed/
| sort @timestamp desc
| limit 100
```

해당 행의 `requestId`로 같은 요청의 공통 HTTP 로그를 찾는다. `stage=transaction_commit`은 서비스 메서드 본문 이후 커밋 과정에서 발생한 오류이며, 로그의 대결·계좌 ID가 롤백 후 DB에 존재하지 않을 수 있다. 실제 CloudWatch 수집 연결은 배포 환경에서 별도 확인해야 한다.

## 시장가 주문 실패 추적

2026-10-22 시장가 복구 브랜치 기준이며 CloudWatch 실제 수집·배포 검증은 별도다.
`OrderController.createMarketOrder()`는 `event=market_order_completed` 또는
`event=market_order_rejected`를 기록한다. 실패 로그에는 stage, code, accountId, stockCode,
side, source, causeType과 기존 MDC requestId가 포함된다. 토큰·본문·AI 판단 근거는 기록하지 않는다.

| stage | 의미 |
|---|---|
| trading_time | 호가 구독 전에 장중 여부 확인 |
| quote_acquisition | AI 매수 평가 가격 조회 및 임시 구독·호가 확보 |
| execution | 호가 확보 후 체결 서비스 호출·검증·저장·커밋 |

주문 성공 로그는 체결 서비스가 커밋하고 임시 수요 정리를 마친 뒤 기록된다.
계좌 소유권·actor·요청 DTO 등 공통 사전 검사에서 실패하면 시장가 전용 메서드에 도달하지 않는다.
그 경우 기존 `api_rejected`, `security_rejected`, `http_request` 로그와 응답 code/message를 확인한다.

| code | 해석 |
|---|---|
| ORDER_WINDOW_CLOSED | 평일 장중이 아니거나 잠금·처리 중 15:30 도달 |
| MARKET_STREAM_UNAVAILABLE | 구독 갱신 실패, 3초 내 호가 미도착, 출처 누락·변경, 대기 중 인터럽트 |
| MARKET_DATA_UNAVAILABLE | 확보한 호가의 수신·거래소 시각 또는 가격 데이터 오류 |
| INSUFFICIENT_CASH | 지정가 예약금을 제외한 현금 부족 |
| INSUFFICIENT_SELLABLE_QUANTITY | 지정가 매도 예약을 제외한 보유 수량 부족 |
| INSUFFICIENT_MARKET_LIQUIDITY | 전량 체결에 필요한 상대 호가 잔량 부족 |
| INTERNAL_ERROR | 예상하지 못한 런타임 오류. causeType과 같은 requestId의 DB·HTTP 로그 확인 |

```text
fields @timestamp, @message
| filter @message like /event=market_order_rejected|event=market_order_completed/
| sort @timestamp desc
| limit 100
```

특정 요청은 응답의 X-Request-Id를 아래 예시 값 대신 넣어 검색한다.

```text
fields @timestamp, @message
| filter @message like /검색할-requestId/
| sort @timestamp asc
| limit 200
```

예: `stage=execution code=INSUFFICIENT_CASH`는 호가 확보 이후 자산 검증 단계의 실패다.
`stage=quote_acquisition code=MARKET_STREAM_UNAVAILABLE`는 체결 전 호가 확보 실패다.
`order_subscription_refresh_failed`만 있고 market_order_completed가 있으면 구독 정리 오류가
거래 결과를 되돌렸다는 의미가 아니다. DB의 커밋된 주문·체결 결과를 기준으로 확인한다.


## 최신 대결·시세 연결부 점검 (2026-11-09)

- 상세 조회는 `CompetitionDetailService`에서 참가자 구성, 보유 데이터, 현재가, 종목정보 실패를 구분한다. `competitionId`, `participantId`, `accountId`, `stockCode`로 실패 대상을 추적한다.
- 결과·일별 성과·정산·리포트 조회는 `competitionType`, 실제 참가자 수, 예상 AI 수 또는 전략 수를 기록한다. AI 간 대결은 2~4명의 AI이며 사용자 참가자가 없는 정상 구성을 오류로 해석하지 않는다.
- 결과 조회의 `participant_validation`, `final_result_validation`, `strategy_validation`을 구분한다. 응답의 참가자별 결과·공동 우승자 목록·전략 원문을 로그로 복사하지 않는다.
- 참가자별 리포트 저장 완료는 서비스 트랜잭션 반환 후 `competition_report_saved`로 기록한다. `participantId`, 뉴스·판단 개수만 남기며 원문은 남기지 않는다. `pending`은 정상 미수신 상태이므로 오류 로그를 남기지 않는다.
- AI 설정 전송은 `eventId`가 메시지 식별자다. `competition_ai_setup_published`는 SQS 수락, `competition_ai_setup_outbox_sent`는 DB 커밋 후 상태 저장 완료다. SQS 수락 뒤 DB 저장이 실패하면 재전송될 수 있다. 실패 단계는 `sqs_send`와 `sent_save`로 구분한다. HTTP MDC의 `requestId`와 메시지 `eventId`를 구분한다.
- 시세 HTTP 연결은 `market_http_failed`의 `stage`로 종가/종목정보 HTTP 실패와 응답 형식 오류, 해당 날짜 종가 미확보를 구분한다. HTTP 실패는 상태·소요 시간을 기록하고 본문·URL·커서는 기록하지 않는다. `httpStatus=0`은 HTTP 응답 상태를 확보하지 못한 실패다.

실제 AWS 전송·AI 콜백·CloudWatch 수집은 로컬 회귀 테스트로 검증되지 않는다.


AI 설정 예약 중 생성 실패는 기존 `competition_creation_failed` 이벤트의 stage로 구분한다.
`ai_setup_strategy_lookup`은 전략 조회·요청 항목 구성,
`ai_setup_strategy_validation`은 전략 개수 확인,
`ai_setup_message_encode`는 메시지 구성·JSON 직렬화,
`ai_setup_outbox_save`는 발송 예약 저장이다.
성공 후에는 기존 호출자가 `transaction_commit`으로 전환한다.
전략·요청 본문·예외 메시지 원문은 추가로 기록하지 않는다.
