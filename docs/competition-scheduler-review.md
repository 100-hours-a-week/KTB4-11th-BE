# 스케줄러 점검 및 개선 현황

## 반영: 대결 정산 실행 스레드 분리

```text
CompetitionSchedulingConfiguration
 → competitionCompletionTaskScheduler 빈 (스레드 1개)
 → CompetitionCompletionScheduler.completeExpiredCompetitions()
   → 대결 완료 정산 우선 처리
   → 일별 성과 누락 복구
```

기존에는 taskScheduler의 단일 스레드에서 대결 정산·대결 시작·주문 시세 구독 갱신·주문 요청 복구가 함께 실행됐다. 정산의 종가 조회나 과거 데이터 복구가 길어지면 다른 예약 작업도 기다려야 했다.

이제 정산 메서드의 @Scheduled.scheduler가 competitionCompletionTaskScheduler를 선택한다. 기존 스케줄러를 직접 호출하거나 업무 로직을 옮긴 것은 아니다. 주기는 기존 1시간 fixedDelay를 유지하고, 완료 작업 비활성 설정도 그대로 따른다.

| 실행 빈 | 스레드 수 | 작업 |
|---|---|---|
| taskScheduler | 1 | 대결 시작, 주문 구독 갱신, 활성화 시 주문 요청 복구 |
| collectorLeaseScheduler | 1 | Redis 수집 담당 소유권 갱신 |
| competitionCompletionTaskScheduler | 1 | 활성화 시 대결 정산·일별 성과 복구 |
| competitionReportTaskScheduler | 1 | Report Queue 활성화 시 리포트 요청 전송 |

이 변경은 실행 스레드 분리다. DB 풀·외부 API 자원 공유, 여러 서버의 실행은 기존과 같으며 다른 작업에 대한 모든 지연을 제거한 것은 아니다. DB 스키마나 추가 환경변수 변경은 없다.

## 검증

실제 Spring 예약 작업으로 정산의 후보 조회를 의도적으로 대기시키고, 그동안 공용 스케줄러에서 다른 작업이 완료되는지 테스트한다. 완료 작업을 끄면 전용 빈을 생성하지 않는지도 확인한다. 운영 실행 시간·부하는 측정하지 않았다.

## 반영: 복구를 마친 완료 대결의 후보 제외

```text
CompetitionRepository.findDailyPerformanceCandidates(today)
 → 진행 중 또는 완료 상태이며 daily_performance_complete=false인 대결
 → CompetitionDailyPerformanceService.recordMissingDays(competitionId)
   → 대결 잠금·완료 표시 재확인
   → 두 참가자의 시작일부터 종료 거래일까지 평일 기록 확인·누락 복구
   → 완료 대결이고 종료 거래일까지 확인했으면 markDailyPerformanceComplete()
 → 기록과 완료 표시를 함께 커밋
 → 다음 주기의 후보에서 제외
```

competitions.daily_performance_complete는 대결의 state와 별개인 내부 복구 완료 표시다. 진행 중 대결은 true로 바꾸지 않으므로 계속 기록 대상이다. 기존 완료 대결도 DB 기본값 false로 시작해 처음 한 번 검증·복구한다. 마지막 날짜 한 건만 존재한다고 복구가 끝난 것으로 판단하지 않는다. 모든 평일의 양쪽 참가자 기록을 확인한다.

누락이 처음부터 없어도 완료 표시를 남긴다. 중간 복구 실패 시 기록과 표시가 함께 롤백돼 다음 주기에 다시 후보가 된다. 여러 서버가 이전에 같은 후보를 조회했더라도 잠금 후 완료 표시를 재확인하고 반환한다. 종료 정산은 마지막 날을 저장할 뿐 전체 이력을 확인하지 않으므로 그 시점에 이 표시를 true로 만들지 않는다. 다음 복구 주기에서 전체 이력을 확인한 후 제외한다.

DB 변경: [competition-daily-performance-complete.sql](../db/competition-daily-performance-complete.sql). 앱 배포 전에 BOOLEAN NOT NULL DEFAULT FALSE 컬럼과 state·완료 표시·start_date 복합 인덱스를 추가해야 한다. 실제 운영 DB에는 적용하지 않았다. 완료로 표시한 과거 일별 기록을 운영자가 별도 삭제·수정하는 경우에는 해당 대결의 표시를 false로 되돌려야 자동 복구 대상에 다시 포함된다. 자동으로 외부 변경을 감지하는 기능은 없다.

DB의 후보 조회 자체는 계속 수행하지만 복구를 마친 완료 대결은 ID 결과에 포함하지 않아, 대결별 잠금·참가자·성과 목록 반복 조회를 줄인다. 운영 데이터에 대한 실행 계획·성능 측정은 하지 않았다.

## 장 마감 실행과 복구

동일 completeExpiredCompetitions()에 한국 시간 평일 15:30 및 15:35 cron 예약을 추가했다. 기존 1시간 fixedDelay도 유지하므로 서버 재시작 시 초기 실행과 이후 주기에서 놓친 완료·일별 기록을 복구한다. 시간대는 Asia/Seoul로 명시했고 cron은 competition.completion.close-cron 또는 COMPETITION_COMPLETION_CLOSE_CRON으로 조정할 수 있다. 기본 cron은 0 30,35 15 * * MON-FRI다.

15:30에는 종료 정산을 시도하고 15:35에는 종가 응답 지연 등 실패분을 다시 확인한다. 성공한 대결은 상태 조건에서 제외된다. 두 번 모두 실패하면 기존 1시간 복구 주기에서 다시 시도한다. 평일·주말만 구분하며 공휴일 처리는 추가하지 않았다.

이제 과거 일별 복구보다 종료 정산을 먼저 한다. 이어서 일별 복구를 실행해 완료 대결의 전체 기록을 확인하고 후보에서 제외한다. 두 예약은 같은 단일 전용 스레드에서 실행하므로 같은 인스턴스에서 동시에 정산하지 않는다. 다만 이전 작업이 길면 cron 실행도 늦어질 수 있고 종가 조회가 실패할 수 있어 정확한 시각의 처리 완료를 보장하지 않는다. fixedDelay도 이전 실행 종료 시점 기준이므로 실제 재시도 간격은 실행 시간의 영향을 받는다.

실제 Spring에 등록된 CronTrigger로 금요일 15:30·15:35 및 다음 월요일 15:30 예약을 검증했다. 과거 복구를 의도적으로 대기시킨 상태에서도 대결 완료 상태가 먼저 커밋되는지 검증했다.

## SQS 잠금·여러 서버 비용 검토

인스턴스당 전송 전용 스레드 1개가 요청별 Outbox 행을 잠그고 SQS 응답을 확인한 뒤 SENT를 저장한다. 여러 서버가 같은 후보를 조회할 수 있으나 잠금 후 상태 재확인으로 일반적인 중복 전송을 막는다. 다른 서버의 행 잠금 대기도 DB 연결을 점유하므로 서버 수가 늘면 조회·대기 비용이 증가한다.

SDK 전체 호출은 10초, Publisher 대기는 최대 12초다. 이 시간은 DB 잠금 대기·전체 실행 시간을 제한하는 값은 아니다. 현재 구조를 유지하고 운영 측정으로 비용을 확인한다. 여기서 잠금만 제거하면 같은 요청을 동시에 보낼 수 있으므로 제거하지 않았다. 네트워크 대기 중 DB 연결을 해제하는 방식은 별도 선점 상태·만료·회수 설계가 필요하다.

competition_report_dispatch_run_finished 로그에 attemptedCount, successCount, failedCount, skippedCount, elapsedMs를 남긴다. skippedCount는 조회 후 다른 서버에서 처리됐거나 삭제돼 전송을 건너뛴 건수다. 후보가 없는 실행은 요약 로그를 생략한다. 실패 원인은 요청별 stage 로그와 연결한다. 이 로그는 한 인스턴스의 실행 요약이며 전체 서버의 전송 건수를 중복 없이 합산하는 지표는 아니다.

운영에서는 반복 실패, 실행 시간 증가, DB 풀 대기, 오래된 PENDING 증가를 확인한다. 실제 부하·임계값은 측정하지 않았으므로 특정 처리량을 보장하지 않는다. 비용이 관측되면 선점 상태·임대 시간 또는 단일 발행 담당 방식으로 확장한다. SQS 성공 후 DB 커밋 실패로 인한 재전송은 현재 방식에서도 발생할 수 있어 AI의 eventId 중복 처리가 필요하다.

## 코드

- [정산 실행 설정](../src/main/java/com/stock_spoon/river_be/competition/config/CompetitionSchedulingConfiguration.java)
- [정산 스케줄러](../src/main/java/com/stock_spoon/river_be/competition/service/CompetitionCompletionScheduler.java)
- [동작 테스트](../src/test/java/com/stock_spoon/river_be/competition/CompetitionSchedulingTests.java)
