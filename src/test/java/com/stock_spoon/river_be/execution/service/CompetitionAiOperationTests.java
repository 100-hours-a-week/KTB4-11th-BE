package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.market.RedisMarketPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CompetitionAiOperationTests {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @Autowired CompetitionDailyPerformanceRepository records;
    @Autowired CompetitionReportOutboxRepository outboxes;
    @Autowired OrderRepository orders;
    @Autowired HoldingRepository holdings;
    @Autowired ExecutionRepository executions;
    @Autowired CompetitionDailyPerformanceService daily;
    @Autowired CompetitionCompletionService completion;
    @Autowired CompetitionCancellationService cancellation;
    @Autowired CompetitionService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean KiwoomMarketClient market;
    @MockitoBean RedisMarketPrice prices;
    @MockitoSpyBean CompetitionTradingHours hours;
    private final LocalDate monday = LocalDate.of(2026, 10, 12);
    private Instant at(String s) { return ZonedDateTime.parse(s).toInstant(); }
    private record Fixture(User user, Competition competition, List<CompetitionParticipant> links) {}
    private Fixture fixture(int n, boolean started) {
        var user = users.save(new User("운용" + UUID.randomUUID().toString().substring(0, 6)));
        var c = new Competition(user, "AI 비교", Competition.Type.AI_VS_AI, monday, monday.plusDays(4), 1_000_000, n);
        // 운영 시작은 계속 차단한다. 이 fixture만 이미 시작된 대결을 재현한다.
        if (started) {
            org.springframework.test.util.ReflectionTestUtils.setField(c, "state", Competition.State.IN_PROGRESS);
            org.springframework.test.util.ReflectionTestUtils.setField(c, "startedAt", at("2026-10-12T09:00:00+09:00"));
        }
        c = competitions.save(c);
        var links = new ArrayList<CompetitionParticipant>();
        for (int i = 0; i < n; i++) {
            var a = accounts.save(new Account(user, "AI " + i, 1_000_000, true));
            var p = participants.save(new CompetitionParticipant(c, a));
            agents.save(new CompetitionAgent(p, i, i + 1, List.of(), List.of(), ""));
            links.add(p);
        }
        return new Fixture(user, c, links);
    }
    private void now(String value) { doReturn(ZonedDateTime.parse(value)).when(hours).now(); }
    private Order pending(CompetitionParticipant p, long quantity) {
        return orders.save(Order.pendingLimit(p.getAccount(), "005930", Order.Side.BUY, quantity, 100_000,
                Order.Source.AI, null, at("2026-10-12T10:00:00+09:00")));
    }
    private void buy(Fixture f, int index, long quantity) {
        var p = f.links().get(index);
        var order = pending(p, quantity);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var engine = new OrderExecutionService(accounts, orders, holdings, executions,
                    Clock.fixed(at("2026-10-12T10:00:00+09:00"), ZoneOffset.UTC));
            assertThat(engine.executeLimit(p.getAccount().getId(), order.getId(), 100_000)).isTrue();
            assertThat(engine.executeLimit(p.getAccount().getId(), order.getId(), 100_000)).isFalse();
        });
    }
    @Test void readyAiAccountsCannotTradeOrStartAutomatically() {
        var f = fixture(2, false);
        var p = f.links().getFirst();
        var order = pending(p, 1);
        var engine = new OrderExecutionService(accounts, orders, holdings, executions,
                Clock.fixed(at("2026-10-12T10:00:00+09:00"), ZoneOffset.UTC));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThat(engine.executeLimit(p.getAccount().getId(), order.getId(), 100_000)).isFalse());
        assertThatThrownBy(() -> f.competition().start(Instant.now())).isInstanceOf(IllegalStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThat(competitions.findWaitingToStart(monday)).extracting(Competition::getId).doesNotContain(f.competition().getId()));
        assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().getCashBalance()).isEqualTo(1_000_000);
    }
    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void recordsIndependentAccountsAndDailyReturns(int n) {
        var f = fixture(n, true);
        for (int i = 0; i < n; i++) buy(f, i, i + 1);
        when(market.closingPrice("005930", monday)).thenReturn(120_000L);
        now("2026-10-12T16:00:00+09:00");
        daily.recordMissingDays(f.competition().getId());
        daily.recordMissingDays(f.competition().getId());
        assertThat(records.findAllByParticipantCompetitionId(f.competition().getId())).hasSize(n);
        for (int i = 0; i < n; i++) {
            var p = f.links().get(i);
            assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().getCashBalance()).isEqualTo(1_000_000 - (i + 1) * 100_000);
            assertThat(holdings.findByAccountIdAndStockCode(p.getAccount().getId(), "005930").orElseThrow().getQuantity()).isEqualTo(i + 1);
            var row = records.findAllByParticipantIdOrderByRecordDateAsc(p.getId()).getFirst();
            assertThat(row.getAssetValue()).isEqualTo(1_000_000 + (i + 1) * 20_000);
        }
    }
    @Test void failedDailyPricesDoNotLeavePartialParticipantRecords() {
        var f = fixture(3, true);
        buy(f, 2, 1);
        when(market.closingPrice("005930", monday)).thenThrow(new IllegalStateException("시세 없음"));
        now("2026-10-12T16:00:00+09:00");
        assertThatThrownBy(() -> daily.recordMissingDays(f.competition().getId())).isInstanceOf(IllegalStateException.class).hasMessage("시세 없음");
        assertThat(records.findAllByParticipantCompetitionId(f.competition().getId())).isEmpty();
    }
    @Test void settlesAllParticipantsAndReturnsTiedWinnersWithoutLegacyReport() {
        var f = fixture(3, true);
        buy(f, 0, 2); buy(f, 1, 2); buy(f, 2, 1);
        when(market.closingPrice("005930", monday.plusDays(4))).thenReturn(120_000L);
        now("2026-10-16T16:00:00+09:00");
        completion.completeCompetition(f.competition().getId());
        completion.completeCompetition(f.competition().getId());
        assertThat(competitions.findById(f.competition().getId()).orElseThrow().getState()).isEqualTo(Competition.State.COMPLETED);
        assertThat(outboxes.findAll()).noneMatch(o -> o.getCompetition().getId().equals(f.competition().getId()));
        assertThat(records.findAllByParticipantCompetitionId(f.competition().getId())).hasSize(3);
        for (var p : f.links()) assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().isActive()).isFalse();
        var body = json.readTree(json.writeValueAsString(service.getCompetitionResult(f.user().getId(), f.competition().getId())));
        assertThat(body.get("winner_participant_ids").size()).isEqualTo(2);
        assertThat(body.get("participants").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(body.get("participants").get(1).get("rank").asInt()).isEqualTo(1);
        assertThat(body.get("participants").get(2).get("rank").asInt()).isEqualTo(3);
        assertThat(body.get("participants").get(0).get("final_asset_value").asLong()).isEqualTo(1_040_000);
        assertThat(body.has("user_final_return_percent")).isFalse();
    }
    @ParameterizedTest @ValueSource(ints = {2, 4})
    void settlesEveryAiWithOneWinnerAndKeepsOtherCompetitionUntouched(int n) {
        var f = fixture(n, true);
        var other = fixture(2, false);
        buy(f, 0, 1);
        when(market.closingPrice("005930", monday.plusDays(4))).thenReturn(120_000L);
        now("2026-10-16T16:00:00+09:00");
        completion.completeCompetition(f.competition().getId());
        var body = json.readTree(json.writeValueAsString(service.getCompetitionResult(f.user().getId(), f.competition().getId())));
        assertThat(body.get("winner_participant_ids").get(0).asLong()).isEqualTo(f.links().getFirst().getId());
        assertThat(body.get("participants").size()).isEqualTo(n);
        for (var p : other.links()) assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().isActive()).isTrue();
    }
    @Test void finalRankingDoesNotTreatRoundedEqualReturnsAsDraw() {
        var user = new User("순위");
        var c = new Competition(user, "비교", Competition.Type.AI_VS_AI, monday, monday.plusDays(4), 100_000_000, 2);
        var first = new CompetitionParticipant(c, new Account(user, "A", 100_000_000, true));
        var second = new CompetitionParticipant(c, new Account(user, "B", 100_000_000, true));
        first.recordFinalAssets(100_000_001); second.recordFinalAssets(100_000_000);
        assertThat(first.getFinalReturnPercent()).isEqualByComparingTo(second.getFinalReturnPercent());
        assertThat(CompetitionParticipants.rank(first, List.of(first, second))).isEqualTo(1);
        assertThat(CompetitionParticipants.rank(second, List.of(first, second))).isEqualTo(2);
    }
    @Test void malformedParticipantCountIsRejectedBeforeAnyFinalChanges() {
        var f = fixture(3, true);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var c = competitions.findById(f.competition().getId()).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(c, "aiParticipantCount", 4);
        });
        now("2026-10-16T16:00:00+09:00");
        assertThatThrownBy(() -> completion.completeCompetition(f.competition().getId())).isInstanceOf(IllegalStateException.class);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(f.competition().getId())).allMatch(p -> p.getFinalAssetValue() == null);
    }
    @Test void completedDetailUsesCurrentAssetsWhileResultKeepsFinalAssets() {
        var f = fixture(2, true);
        buy(f, 0, 1);
        now("2026-10-16T16:00:00+09:00");
        when(market.closingPrice("005930", monday.plusDays(4))).thenReturn(110_000L);
        completion.completeCompetition(f.competition().getId());
        when(prices.currentPrice("005930")).thenReturn(120_000L);
        when(market.stockDetails("005930")).thenReturn(new KiwoomMarketClient.StockDetails("005930", "삼성전자", null));
        var detail = json.readTree(json.writeValueAsString(service.getCompetitionDetail(f.user().getId(), f.competition().getId())));
        var result = json.readTree(json.writeValueAsString(service.getCompetitionResult(f.user().getId(), f.competition().getId())));
        assertThat(detail.get("participants").get(0).get("total_assets").asLong()).isEqualTo(1_020_000);
        assertThat(result.get("participants").get(0).get("final_asset_value").asLong()).isEqualTo(1_010_000);
    }
    @Test void resultRejectsDuplicateAgentPositionsInsteadOfAmbiguousLabels() {
        var f = fixture(2, true);
        now("2026-10-16T16:00:00+09:00");
        completion.completeCompetition(f.competition().getId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var settings = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(f.competition().getId());
            org.springframework.test.util.ReflectionTestUtils.setField(settings.get(1), "position", 0);
        });
        assertThatThrownBy(() -> service.getCompetitionResult(f.user().getId(), f.competition().getId()))
                .isInstanceOf(com.stock_spoon.river_be.competition.exception.CompetitionException.class);
    }
    @Test void settlementRejectsMissingStrategyBeforeFreezingResults() {
        var f = fixture(2, true);
        var setting = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(f.competition().getId()).getFirst();
        agents.deleteById(setting.getId());
        now("2026-10-16T16:00:00+09:00");
        assertThatThrownBy(() -> completion.completeCompetition(f.competition().getId())).isInstanceOf(IllegalStateException.class);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(f.competition().getId())).allMatch(p -> p.getFinalAssetValue() == null);
    }
    @Test void failedSettlementRollsBackFinalResultsAndAccountDeactivation() {
        var f = fixture(3, true);
        buy(f, 2, 1);
        now("2026-10-16T16:00:00+09:00");
        when(market.closingPrice("005930", monday.plusDays(4))).thenThrow(new IllegalStateException("시세 없음"));
        assertThatThrownBy(() -> completion.completeCompetition(f.competition().getId())).isInstanceOf(IllegalStateException.class).hasMessage("시세 없음");
        assertThat(competitions.findById(f.competition().getId()).orElseThrow().getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(f.competition().getId())).allMatch(p -> p.getFinalAssetValue() == null);
        for (var p : f.links()) assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().isActive()).isTrue();
    }
    @Test void cancelsEveryAiAccountAndPendingOrderIdempotently() {
        var f = fixture(4, false);
        var pending = f.links().stream().map(p -> pending(p, 1)).toList();
        cancellation.cancel(f.user().getId(), f.competition().getId());
        cancellation.cancel(f.user().getId(), f.competition().getId());
        for (var p : f.links()) assertThat(accounts.findById(p.getAccount().getId()).orElseThrow().isActive()).isFalse();
        for (var o : pending) assertThat(orders.findById(o.getId()).orElseThrow().getStatus()).isEqualTo(Order.Status.CANCELLED);
    }
    @Test void detailReturnsLiveAssetsAndSeparateParticipantHistory() {
        var f = fixture(2, true);
        buy(f, 0, 1);
        when(prices.currentPrice("005930")).thenReturn(120_000L);
        when(market.closingPrice("005930", monday)).thenReturn(110_000L);
        now("2026-10-12T16:00:00+09:00");
        daily.recordMissingDays(f.competition().getId());
        // 이름 조회 없이 보유분 없는 두 번째 계좌와 기록도 함께 확인한다.
        when(market.stockDetails("005930")).thenReturn(new KiwoomMarketClient.StockDetails("005930", "삼성전자", null));
        var body = json.readTree(json.writeValueAsString(service.getCompetitionDetail(f.user().getId(), f.competition().getId())));
        assertThat(body.get("participants").get(0).get("total_assets").asLong()).isEqualTo(1_020_000);
        assertThat(body.get("participants").get(1).get("total_assets").asLong()).isEqualTo(1_000_000);
        assertThat(body.get("participants").get(0).get("daily_performances").size()).isEqualTo(1);
        assertThat(body.get("participants").get(0).get("daily_performances").get(0).get("cumulative_return_percent").asDouble()).isEqualTo(1.0);
    }
}
