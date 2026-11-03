package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "competition.report.queue.enabled", havingValue = "true")
public class CompetitionReportDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(CompetitionReportDispatchScheduler.class);
    private final CompetitionReportOutboxRepository outboxes;
    private final CompetitionReportDispatchService dispatch;
    private final int batchSize;
    private final boolean enabled;

    public CompetitionReportDispatchScheduler(CompetitionReportOutboxRepository outboxes, CompetitionReportDispatchService dispatch,
            @Value("${competition.report.dispatch.batch-size:20}") int batchSize,
            @Value("${competition.report.dispatch.enabled:true}") boolean enabled) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("리포트 전송 조회 개수는 1~100이어야 합니다.");
        this.outboxes = outboxes;
        this.dispatch = dispatch;
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${competition.report.dispatch.interval-ms:60000}", scheduler = "competitionReportTaskScheduler")
    public void dispatchPendingReports() {
        if (!enabled) return;
        long started = System.nanoTime();
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        try {
            Long upperId = outboxes.findPendingUpperBound();
            if (upperId == null) return;
            long afterId = 0;
            while (!Thread.currentThread().isInterrupted()) {
                var ids = outboxes.findPendingIdsBetween(afterId, upperId, PageRequest.of(0, batchSize));
                if (ids.isEmpty()) return;
                for (Long id : ids) {
                    if (Thread.currentThread().isInterrupted()) return;
                    attempted++;
                    try {
                        // 별도 서비스 프록시가 요청별 트랜잭션을 커밋한 후 성공 로그를 남긴다.
                        String requestId = dispatch.dispatch(id);
                        if (requestId != null) {
                            succeeded++;
                            log.info("event=competition_report_outbox_sent outboxId={} requestId={}", id, requestId);
                        }
                    } catch (RuntimeException error) {
                        failed++;
                        log.error("event=competition_report_dispatch_failed stage=dispatch_transaction outboxId={} causeType={}",
                                id, error.getClass().getSimpleName(), error);
                    }
                    afterId = id;
                }
            }
        } catch (RuntimeException error) {
            log.error("event=competition_report_dispatch_failed stage=candidate_query causeType={}", error.getClass().getSimpleName(), error);
        } finally {
            if (attempted > 0) {
                log.info("event=competition_report_dispatch_run_finished attemptedCount={} successCount={} failedCount={} skippedCount={} elapsedMs={}",
                        attempted, succeeded, failed, attempted - succeeded - failed,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }
}
