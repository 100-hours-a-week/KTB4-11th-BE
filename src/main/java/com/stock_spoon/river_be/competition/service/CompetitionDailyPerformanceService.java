package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionDailyPerformance;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.entity.Order;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionDailyPerformanceService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final CompetitionDailyPerformanceRepository performances;
    private final AccountRepository accounts;
    private final ExecutionRepository executions;
    private final KiwoomMarketClient market;
    private final CompetitionTradingHours tradingHours;

    // 같은 대결의 정산·기록 작업은 대결 잠금으로 순서를 지킨다. 실패한 대결의 새 기록은 함께 롤백한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordMissingDays(Long competitionId) {
        var competition = competitions.findLockedById(competitionId).orElseThrow();
        if (competition.getStartedAt() == null || (competition.getState() != Competition.State.IN_PROGRESS
                && competition.getState() != Competition.State.COMPLETED)) return;
        var now = tradingHours.now();
        LocalDate lastDate = now.toLocalTime().isBefore(LocalTime.of(15, 30))
                ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        if (lastDate.isAfter(competition.tradingEndDate())) lastDate = competition.tradingEndDate();
        // 마지막 날은 종료 정산 서비스가 최종 성적과 함께 기록한다.
        if (lastDate.equals(competition.tradingEndDate()) && competition.getState() != Competition.State.COMPLETED) {
            lastDate = lastDate.minusDays(1);
        }

        var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
        if (links.size() != 2 || links.stream().filter(p -> p.getAccount().isAiManaged()).count() != 1) {
            throw new IllegalStateException("사용자와 AI 참가자를 확인할 수 없습니다.");
        }
        // {참가자 ID: 이미 저장된 날짜들}. 양쪽 모두 기록된 날짜는 다시 조회·평가하지 않는다.
        var recorded = new HashMap<Long, Set<LocalDate>>();
        for (var row : performances.findAllByParticipantCompetitionId(competitionId)) {
            recorded.computeIfAbsent(row.getParticipant().getId(), key -> new HashSet<>()).add(row.getRecordDate());
        }
        var missingDays = new ArrayList<LocalDate>();
        for (LocalDate date = competition.getStartedAt().atZone(SEOUL).toLocalDate();
                !date.isAfter(lastDate); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            LocalDate day = date;
            if (links.stream().anyMatch(p -> !recorded.getOrDefault(p.getId(), Set.of()).contains(day))) missingDays.add(day);
        }
        if (missingDays.isEmpty()) return;

        // 장 마감 직전부터 진행하던 체결이 끝날 때까지 같은 계좌 잠금으로 기다린다.
        var accountIds = links.stream().map(p -> p.getAccount().getId()).sorted().toList();
        for (Long id : accountIds) accounts.findLockedById(id).orElseThrow();
        var cash = new HashMap<Long, Long>();
        var quantities = new HashMap<Long, Map<String, Long>>();
        for (var link : links) {
            cash.put(link.getAccount().getId(), link.getAccount().getInitialCapital());
            quantities.put(link.getAccount().getId(), new HashMap<>());
        }
        var trades = executions.findPerformanceTrades(accountIds, closeAt(missingDays.getLast()));
        int tradeIndex = 0;
        for (LocalDate day : missingDays) {
            // 날짜가 앞으로 갈 때 새 체결만 추가 반영한다. 전량 매도한 종목은 평가액이 0이다.
            while (tradeIndex < trades.size() && trades.get(tradeIndex).getCreatedAt().isBefore(closeAt(day))) {
                applyTrade(trades.get(tradeIndex++), cash, quantities);
            }
            var prices = new HashMap<String, Long>();
            for (var link : links) {
                if (recorded.getOrDefault(link.getId(), Set.of()).contains(day)) continue;
                long assets;
                if (competition.getState() == Competition.State.COMPLETED && day.equals(competition.tradingEndDate())) {
                    // 기능 도입 전 완료된 대결도 저장된 최종 성적과 같은 값으로 복구한다.
                    if (link.getFinalAssetValue() == null) throw new IllegalStateException("최종 성적이 없습니다.");
                    assets = link.getFinalAssetValue();
                } else {
                    Long accountId = link.getAccount().getId();
                    assets = cash.get(accountId);
                    for (var held : quantities.get(accountId).entrySet()) {
                        if (held.getValue() == 0) continue;
                        long price = prices.computeIfAbsent(held.getKey(), code -> market.closingPrice(code, day));
                        if (price <= 0) throw new IllegalStateException("해당일 종가를 확인할 수 없습니다.");
                        assets = Math.addExact(assets, Math.multiplyExact(price, held.getValue()));
                    }
                }
                performances.save(new CompetitionDailyPerformance(link, day, assets));
            }
        }
    }

    // 종료 정산의 같은 트랜잭션에서 호출해 최종 성적과 그래프 마지막 점을 동시에 저장한다.
    @Transactional
    public void recordFinalPerformances(Long competitionId) {
        var competition = competitions.findLockedById(competitionId).orElseThrow();
        if (competition.getState() != Competition.State.COMPLETED) throw new IllegalStateException("완료 대결만 기록할 수 있습니다.");
        for (var link : participants.findAllByCompetitionIdOrderByIdAsc(competitionId)) {
            if (link.getFinalAssetValue() == null) throw new IllegalStateException("최종 성적이 없습니다.");
            var existing = performances.findByParticipantIdAndRecordDate(link.getId(), competition.tradingEndDate());
            if (existing.isPresent()) {
                if (existing.get().getAssetValue() != link.getFinalAssetValue()) throw new IllegalStateException("최종 기록이 일치하지 않습니다.");
                continue;
            }
            performances.save(new CompetitionDailyPerformance(link, competition.tradingEndDate(), link.getFinalAssetValue()));
        }
    }

    private void applyTrade(ExecutionRepository.PerformanceTrade trade, Map<Long, Long> cash,
            Map<Long, Map<String, Long>> quantities) {
        boolean buy = trade.getSide() == Order.Side.BUY;
        long amount = Math.multiplyExact(trade.getPrice(), trade.getQuantity());
        long balance = buy ? Math.subtractExact(cash.get(trade.getAccountId()), amount)
                : Math.addExact(cash.get(trade.getAccountId()), amount);
        long quantity = quantities.get(trade.getAccountId()).merge(trade.getStockCode(),
                buy ? trade.getQuantity() : -trade.getQuantity(), Math::addExact);
        if (balance < 0 || quantity < 0) throw new IllegalStateException("과거 체결 내역과 자산이 일치하지 않습니다.");
        cash.put(trade.getAccountId(), balance);
    }

    private Instant closeAt(LocalDate day) {
        return day.atTime(15, 30).atZone(SEOUL).toInstant();
    }
}
