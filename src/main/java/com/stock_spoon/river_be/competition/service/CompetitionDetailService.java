package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionDetailResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionReturnPercent;
import com.stock_spoon.river_be.competition.dto.CompetitionDetailResponse.*;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.market.RedisMarketPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import java.math.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB에서 응답용 값만 복사한 후 트랜잭션 밖에서 현재 보유분을 평가한다. */
@Service
public class CompetitionDetailService {
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final CompetitionDailyPerformanceRepository performances;
    private final CompetitionAgentRepository agents;
    private final HoldingRepository holdings;
    private final OrderRepository orders;
    private final CompetitionTradingHours hours;
    private final RedisMarketPrice prices;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;

    public CompetitionDetailService(CompetitionRepository competitions,
            CompetitionParticipantRepository participants, CompetitionDailyPerformanceRepository performances,
            CompetitionAgentRepository agents, HoldingRepository holdings, OrderRepository orders,
            CompetitionTradingHours hours, RedisMarketPrice prices, KiwoomMarketClient market,
            PlatformTransactionManager transactions) {
        this.competitions = competitions;
        this.participants = participants;
        this.performances = performances;
        this.agents = agents;
        this.holdings = holdings;
        this.orders = orders;
        this.hours = hours;
        this.prices = prices;
        this.market = market;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
    }

    public CompetitionDetailResponse get(long userId, long competitionId) {
        Snapshot snapshot = Objects.requireNonNull(read.execute(status -> snapshot(userId, competitionId)));
        var priceCache = new HashMap<String, Long>();
        var nameCache = new HashMap<String, String>();
        var items = snapshot.participants().stream().map(p -> {
            var item = new ParticipantItem(p.id(), p.accountId(), p.ai() ? "ai" : "user", p.name(), p.count(), p.strategy(),
                    topHolding(p.holdings(), priceCache, nameCache));
            long assets = p.cash();
            for (var holding : p.holdings()) assets = Math.addExact(assets,
                    Math.multiplyExact(priceCache.get(holding.code()), holding.quantity()));
            item.setTotalAssets(assets);
            item.setCumulativeReturnPercent(CompetitionReturnPercent.fromAssets(assets, p.initial()));
            item.setDailyPerformances(p.history());
            return item;
        }).toList();
        return new CompetitionDetailResponse(snapshot.id(), snapshot.name(), snapshot.type(), snapshot.state(),
                snapshot.start(), snapshot.end(), snapshot.dDay(), items);
    }

    private Snapshot snapshot(long userId, long competitionId) {
        var competition = competitions.findByIdAndUserId(competitionId, userId)
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
        var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
        boolean aiOnly = competition.getCompetitionType() == Competition.Type.AI_VS_AI;
        var settings = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(competitionId);
        var strategies = new HashMap<Long, Strategy>();
        var positions = new HashMap<Long, Integer>();
        for (var agent : settings) {
            long id = agent.getParticipant().getId();
            strategies.put(id, new Strategy(agent.getRiskLevel(), List.copyOf(agent.getPreferredThemes()),
                    List.copyOf(agent.getExcludedThemes()), agent.getAdditionalRequest()));
            positions.put(id, agent.getPosition());
        }
        long userCount = links.stream().filter(p -> !p.getAccount().isAiManaged()).count();
        boolean valid = links.size() == 2 && userCount == 1;
        if (aiOnly) {
            // 생성 순서 0..N-1과 참가자별 전략이 모두 있어야 AI 번호를 안정적으로 반환한다.
            valid = links.size() == competition.getAiParticipantCount() && links.size() >= 2 && links.size() <= 4
                    && userCount == 0 && strategies.size() == links.size()
                    && links.stream().allMatch(p -> positions.containsKey(p.getId()));
            var uniquePositions = new HashSet<>(positions.values());
            for (int i = 0; i < links.size(); i++) valid &= uniquePositions.contains(i);
        }
        if (!valid) {
            throw new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INVALID_COMPETITION_PARTICIPANTS", "대결 참가자 정보를 확인할 수 없습니다.");
        }
        if (aiOnly) links = links.stream().sorted(Comparator.comparingInt(p -> positions.get(p.getId()))).toList();
        // 계좌별 횟수와 보유분은 각각 한 번에 읽어 참가자 수만큼 반복 조회하지 않는다.
        var accountIds = links.stream().map(p -> p.getAccount().getId()).toList();
        var counts = new HashMap<Long, Long>();
        for (var count : orders.countByAccountsAndStatus(accountIds, Order.Status.EXECUTED)) {
            counts.put(count.getAccountId(), count.getTradeCount());
        }
        var owned = new HashMap<Long, List<Owned>>();
        for (var holding : holdings.findAllByAccountIds(accountIds)) {
            owned.computeIfAbsent(holding.getAccountId(), key -> new ArrayList<>()).add(
                    new Owned(holding.getStockCode(), holding.getQuantity(), holding.getTotalCost()));
        }
        var historyByParticipant = performances.findAllByParticipantCompetitionId(competitionId).stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> r.getParticipant().getId()));
        var items = links.stream().map(p -> new Participant(p.getId(), p.getAccount().getId(),
                p.getAccount().isAiManaged(), aiOnly ? "AI " + (positions.get(p.getId()) + 1)
                        : p.getAccount().isAiManaged() ? "AI" : "나",
                counts.getOrDefault(p.getAccount().getId(), 0L), strategies.get(p.getId()),
                List.copyOf(owned.getOrDefault(p.getAccount().getId(), List.of())),
                p.getAccount().getCashBalance(), p.getAccount().getInitialCapital(),
                historyByParticipant.getOrDefault(p.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(com.stock_spoon.river_be.competition.entity.CompetitionDailyPerformance::getRecordDate))
                    .map(r -> new DailyPerformanceItem(r.getRecordDate(), CompetitionReturnPercent.fromAssets(r.getAssetValue(), p.getAccount().getInitialCapital()))).toList())).toList();
        boolean closed = competition.getState() == Competition.State.COMPLETED || competition.getState() == Competition.State.CANCELLED;
        Integer dDay = closed ? null : Math.toIntExact(Math.max(0,
                ChronoUnit.DAYS.between(hours.now().toLocalDate(), competition.getEndDate())));
        return new Snapshot(competition.getId(), competition.getCompetitionName(),
                competition.getCompetitionType().name().toLowerCase(Locale.ROOT),
                competition.getState().name().toLowerCase(Locale.ROOT), competition.getStartDate(),
                competition.getEndDate(), dDay, items);
    }

    private TopReturnHolding topHolding(List<Owned> owned, Map<String, Long> priceCache, Map<String, String> nameCache) {
        Owned best = null;
        BigDecimal bestRate = null;
        for (var row : owned) {
            if (row.quantity() <= 0 || row.cost() == null || row.cost().signum() <= 0) {
                throw new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INVALID_HOLDINGS_DATA", "보유 데이터를 확인해주세요.");
            }
            long price;
            try {
                price = priceCache.computeIfAbsent(row.code(), prices::currentPrice);
            } catch (IllegalStateException error) {
                throw unavailable();
            }
            if (price <= 0) throw unavailable();
            var value = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(row.quantity()));
            var rate = value.subtract(row.cost()).multiply(BigDecimal.valueOf(100)).divide(row.cost(), MathContext.DECIMAL128);
            if (best == null || rate.compareTo(bestRate) > 0
                    || rate.compareTo(bestRate) == 0 && row.code().compareTo(best.code()) < 0) {
                best = row;
                bestRate = rate;
            }
        }
        if (best == null) return null;
        String name;
        try {
            name = nameCache.computeIfAbsent(best.code(), code -> market.stockDetails(code).stockName());
        } catch (IllegalStateException error) {
            throw unavailable();
        }
        if (name == null || name.isBlank()) throw unavailable();
        return new TopReturnHolding(best.code(), name, bestRate);
    }

    private CompetitionException unavailable() {
        return new CompetitionException(HttpStatus.SERVICE_UNAVAILABLE,
                "HOLDINGS_DATA_UNAVAILABLE", "보유 종목 정보를 조회할 수 없습니다.");
    }

    private record Owned(String code, long quantity, BigDecimal cost) {}
    private record Participant(long id, long accountId, boolean ai, String name, long count,
            Strategy strategy, List<Owned> holdings, long cash, long initial, List<DailyPerformanceItem> history) {}
    private record Snapshot(long id, String name, String type, String state, java.time.LocalDate start,
            java.time.LocalDate end, Integer dDay, List<Participant> participants) {}
}
