# Redis 문서 안내

Redis 전환의 로컬 검증 후 인프라의 Serverless IAM 환경변수를 전달받아 배포 연결·인증 코드를 보완했다. 실제 인스턴스·MySQL·배포 Valkey 검증은 별도다.

| 목적 | 문서 |
|---|---|
| AI 현재가·호가 키·JSON·체결 경계 | [AI Valkey 호환](ai-quotes.md) |
| 최신 검증 결과와 남은 단계 | [배포 준비](redis-deployment-checklist.md) |
| 인프라 인계·설정·배포·장애 복구 | [운영 인계](operations-handoff.md) |
| IAM 토큰·TLS·클러스터 연결 및 환경변수 | [IAM 연결](iam-connection.md) |
| 키·채널·명령 ACL 요청 | [Valkey 권한 요구 사항](valkey-acl-requirements.md) |
| 로컬 사용·테스트 실행 | [로컬 Valkey](local-valkey.md) |
| REST 호출 제한 근거 | [공식 호출 제한 확인](kiwoom-call-limit-verification.md) |
| REST 대기·부하 결과 | [REST 측정](rest-load-measurement.md) |
| 시세 전달·주문 체결·성능 개선 결과 | [시세·주문 측정](quote-order-load-measurement.md) |
| PR 본문 초안 | [PR 초안](pull-request-draft.md) |
| 기존 메모리 데이터 조사 기록 | [메모리 데이터 및 계획](in-memory-data-and-redis-plan.md) |
| 단계별 설계 기록 | [설계 초안](redis-transition-design-draft.md) |

배포 환경변수 예시는 [공통](deployment-common.env.example), [담당](deployment-collector.env.example), [일반 BE](deployment-consumer.env.example)로 나뉜다. 실제 값과 비밀값을 예시 파일에 저장하지 않는다.
