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
| filter @message like /event=kiwoom_query_failed|event=kiwoom_query_rejected|event=kospi_refresh_failed/
| sort @timestamp desc
| limit 100
```

requestId를 확인한 뒤 같은 값으로 검색하면 요청 전체 흐름을 확인할 수 있다.
인프라 확인: 배포 후 API 한 번 호출 → X-Request-Id 확인 → CloudWatch에서 해당 ID 검색.
토큰 발급이 아닌 일반 조회 실패 테스트로 HTTP 상태와 TR ID가 보이는지 확인한다.
