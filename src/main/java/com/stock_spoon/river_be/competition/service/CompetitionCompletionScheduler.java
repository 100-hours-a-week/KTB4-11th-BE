package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "competition.completion.enabled", havingValue = "true", matchIfMissing = true)
public class CompetitionCompletionScheduler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionCompletionScheduler.class);
    private final CompetitionRepository competitions;
    private final CompetitionCompletionService completion;
    private final CompetitionTradingHours tradingHours;
    private final CompetitionDailyPerformanceService dailyPerformances;

    @Scheduled(fixedDelayString = "${competition.completion.interval-ms:3600000}", scheduler = "competitionCompletionTaskScheduler")
    @Scheduled(cron = "${competition.completion.close-cron:0 30,35 15 * * MON-FRI}", zone = "Asia/Seoul", scheduler = "competitionCompletionTaskScheduler")
    public void completeExpiredCompetitions() {
        // 당일 종료를 먼저 확정한다. 과거 일별 기록 복구가 길어져도 정산이 뒤로 밀리지 않게 한다.
        // 금요일에 일요일 종료 대결도 찾는다. 서버 재시작 후에는 과거 미처리 대결도 다시 찾는다.
        var latest = tradingHours.now().toLocalDate().plusDays(2);
        for (Long id : competitions.findCompletionCandidates(latest)) {
            try {
                completion.completeCompetition(id);
            } catch (RuntimeException error) {
                // 종가 미제공·일시 장애는 완료로 만들지 않고 다음 주기에 재시도한다.
                log.warn("event=competition_completion_failed stage=completion_transaction competitionId={} causeType={}",
                        id, error.getClass().getSimpleName(), error);
            }
        }
        // 정산 후 진행 중 대결 및 전체 기록 확인이 끝나지 않은 완료 대결을 복구한다.
        for (Long id : competitions.findDailyPerformanceCandidates(tradingHours.now().toLocalDate())) {
            try {
                dailyPerformances.recordMissingDays(id);
            } catch (RuntimeException error) {
                log.warn("event=competition_daily_performance_failed competitionId={} causeType={}",
                        id, error.getClass().getSimpleName(), error);
            }
        }
    }
}
