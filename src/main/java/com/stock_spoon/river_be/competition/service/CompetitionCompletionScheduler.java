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

    @Scheduled(fixedDelayString = "${competition.completion.interval-ms:3600000}")
    public void completeExpiredCompetitions() {
        // 금요일에 일요일 종료 대결도 찾는다. 서버 재시작 후에는 과거 미처리 대결도 다시 찾는다.
        var latest = tradingHours.now().toLocalDate().plusDays(2);
        for (Long id : competitions.findCompletionCandidates(latest)) {
            try {
                completion.completeCompetition(id);
            } catch (RuntimeException error) {
                // 종가 미제공·일시 장애는 완료로 만들지 않고 다음 주기에 재시도한다.
                log.warn("event=competition_completion_failed competitionId={} causeType={}", id, error.getClass().getSimpleName());
            }
        }
    }
}
