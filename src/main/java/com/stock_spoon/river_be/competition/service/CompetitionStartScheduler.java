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
            // DB 잠금을 기다리는 사이 장이 닫히거나 날짜가 바뀔 수 있다.
            var startTime = tradingHours.now();
            if (!tradingHours.isOpen(startTime) || startTime.toLocalDate().isBefore(competition.getStartDate())
                    || !startTime.toInstant().isBefore(competition.tradingEndsAt())) continue;
            competition.start(startTime.toInstant());
        }
        // 조회한 엔티티는 JPA가 관리하므로 변경 내용을 트랜잭션 종료 시 자동 저장한다.
    }
}
