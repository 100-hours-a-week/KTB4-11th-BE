package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "competition.start.enabled", havingValue = "true", matchIfMissing = true)
public class CompetitionStartScheduler {
    private final CompetitionRepository competitions;
    private final CompetitionTradingHours tradingHours;

    @Scheduled(fixedDelayString = "${competition.start.interval-ms:10000}")
    @Transactional
    public void startWaitingCompetitions() {
        var now = tradingHours.now();
        if (!tradingHours.isOpen(now)) return;
        // 종료일은 연장하지 않는다. 대기 중 종료일을 넘긴 건은 이 시작 처리에서 제외한다.
        for (var competition : competitions.findWaitingToStart(now.toLocalDate())) {
            startIfEligible(competition, tradingHours);
        }
        // 조회한 엔티티는 JPA가 관리하므로 변경 내용을 트랜잭션 종료 시 자동 저장한다.
    }

    static void startIfEligible(com.stock_spoon.river_be.competition.entity.Competition competition,
                                CompetitionTradingHours hours) {
        // 호출자는 대결 잠금과 SENT를 확인한다. 시각·상태 검사는 즉시 시작과 복구 경로가 공유한다.
        var now = hours.now();
        if (competition.getState() != com.stock_spoon.river_be.competition.entity.Competition.State.READY
                || !hours.isOpen(now) || now.toLocalDate().isBefore(competition.getStartDate())
                || !now.toInstant().isBefore(competition.tradingEndsAt())) return;
        competition.start(now.toInstant());
    }
}
