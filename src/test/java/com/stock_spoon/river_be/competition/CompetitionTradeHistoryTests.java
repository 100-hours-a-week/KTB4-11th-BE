package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** 실제 HTTP로 참가 계좌 조회 → 주문 합산 → AI 리포트 이동을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompetitionTradeHistoryTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @MockitoSpyBean CompetitionTradeHistoryRepository tradeHistory;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean KiwoomMarketClient market;
    private final HttpClient client = HttpClient.newHttpClient();
    private User owner;
    private Account mine;
    private Account ai;
    private Competition competition;
    private static final Instant TIME = Instant.parse("2026-10-16T06:00:00Z");

    @BeforeEach
    void setup() {
        owner = users.save(new User("매매테스트"));
        mine = accounts.save(new Account(owner, "사용자", 10_000_000, false));
        ai = accounts.save(new Account(owner, "AI", 10_000_000, true));
        competition = new Competition(owner, "매매 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16), 10_000_000, 1);
        competition.start(TIME.minusSeconds(86400));
        competition = competitions.save(competition);
        participants.save(new CompetitionParticipant(competition, mine));
        participants.save(new CompetitionParticipant(competition, ai));
        when(market.stockDetails(anyString())).thenReturn(new KiwoomMarketClient.StockDetails("005930", "삼성전자", "전자"));
    }

    @Test
    void pagesByOrderWithoutSplittingFillsAndContinuesAfterNewExecution() throws Exception {
        var older = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(older, 100, 10, null, null, TIME));
        var newer = order(mine, Order.Side.SELL, true);
        executions.save(new Execution(newer, 120, 4, new BigDecimal("80"), null, TIME));
        executions.save(new Execution(newer, 130, 6, new BigDecimal("120"), null, TIME));
        String first = get(path(mine) + "?limit=1", owner, 200);
        verify(tradeHistory).findExecutedTrades(mine.getId(), List.of(newer.getId()));
        assertThat((List<?>) JsonPath.read(first, "$.executions")).hasSize(1);
        assertThat(((Number) JsonPath.read(first, "$.executions[0].order_id")).longValue()).isEqualTo(newer.getId());
        assertThat((Number) JsonPath.read(first, "$.executions[0].execution_quantity")).isEqualTo(10);
        assertThat((Number) JsonPath.read(first, "$.executions[0].execution_amount")).isEqualTo(1260);
        assertThat((Boolean) JsonPath.read(first, "$.pagination.has_next")).isTrue();
        String cursor = JsonPath.read(first, "$.pagination.next_cursor");

        var added = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(added, 150, 10, null, null, TIME.plusSeconds(60)));
        String last = get(path(mine) + "?cursor=" + cursor, owner, 200);
        assertThat((List<?>) JsonPath.read(last, "$.executions")).hasSize(1);
        assertThat(((Number) JsonPath.read(last, "$.executions[0].order_id")).longValue()).isEqualTo(older.getId());
        assertThat((Number) JsonPath.read(last, "$.pagination.limit")).isEqualTo(10);
        assertThat((Boolean) JsonPath.read(last, "$.pagination.has_next")).isFalse();
        assertThat((Object) JsonPath.read(last, "$.pagination.next_cursor")).isNull();
        get(path(ai) + "?cursor=" + cursor, owner, 403);
        competition.complete(Competition.Winner.DRAW, TIME.plusSeconds(3600));
        competitions.save(competition);
        get(path(ai) + "?cursor=" + cursor, owner, 400);
        String refreshed = get(path(mine) + "?limit=1", owner, 200);
        assertThat(((Number) JsonPath.read(refreshed, "$.executions[0].order_id")).longValue()).isEqualTo(added.getId());
    }

    @Test
    void validatesOrderSide() throws Exception {
        for (String side : List.of("buy", "sell")) {
            get(path(mine) + "?order_side=" + side, owner, 200);
        }
        for (String side : List.of("", "BUY", "SELL", "unknown")) {
            String body = get(path(mine) + "?order_side=" + side, owner, 400);
            assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("INVALID_TRADE_HISTORY_ORDER_SIDE");
        }
    }

    @Test
    void filtersOrdersBeforePaginationAndKeepsCompleteFillTotals() throws Exception {
        var olderBuy = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(olderBuy, 100, 10, null, null, TIME));
        var sell = order(mine, Order.Side.SELL, true);
        executions.save(new Execution(sell, 120, 10, BigDecimal.valueOf(200), BigDecimal.valueOf(20), TIME.plusSeconds(1)));
        var newerBuy = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(newerBuy, 100, 4, null, null, TIME.plusSeconds(2)));
        executions.save(new Execution(newerBuy, 110, 6, null, null, TIME.plusSeconds(2)));

        String first = get(path(mine) + "?order_side=buy&limit=1", owner, 200);
        assertThat(((Number) JsonPath.read(first, "$.executions[0].order_id")).longValue()).isEqualTo(newerBuy.getId());
        assertThat((Number) JsonPath.read(first, "$.executions[0].execution_amount")).isEqualTo(1060);
        assertThat((Boolean) JsonPath.read(first, "$.pagination.has_next")).isTrue();
        String cursor = JsonPath.read(first, "$.pagination.next_cursor");
        String last = get(path(mine) + "?order_side=buy&limit=1&cursor=" + cursor, owner, 200);
        for (String changedFilter : List.of("", "&order_side=sell")) {
            String failure = get(path(mine) + "?limit=1&cursor=" + cursor + changedFilter, owner, 400);
            assertThat((String) JsonPath.read(failure, "$.code")).isEqualTo("INVALID_TRADE_HISTORY_CURSOR");
        }
        assertThat((List<?>) JsonPath.read(last, "$.executions")).hasSize(1);
        assertThat(((Number) JsonPath.read(last, "$.executions[0].order_id")).longValue()).isEqualTo(olderBuy.getId());
        assertThat((Boolean) JsonPath.read(last, "$.pagination.has_next")).isFalse();

        String sells = get(path(mine) + "?order_side=sell&limit=1", owner, 200);
        assertThat((List<?>) JsonPath.read(sells, "$.executions")).hasSize(1);
        assertThat(((Number) JsonPath.read(sells, "$.executions[0].order_id")).longValue()).isEqualTo(sell.getId());
        assertThat((Boolean) JsonPath.read(sells, "$.pagination.has_next")).isFalse();
        String buys = get(path(mine) + "?order_side=buy", owner, 200);
        assertThat((List<?>) JsonPath.read(buys, "$.executions")).hasSize(2);
        assertThat(JsonPath.<Map<String, Object>>read(buys, "$")).doesNotContainKey("pagination");
        assertThat((List<?>) JsonPath.read(get(path(mine), owner, 200), "$.executions")).hasSize(3);
    }

    @Test
    void followsSellPagesAndReturnsEmptyFilteredHistory() throws Exception {
        String empty = get(path(mine) + "?order_side=sell&limit=1", owner, 200);
        assertThat((List<?>) JsonPath.read(empty, "$.executions")).isEmpty();
        assertThat((Boolean) JsonPath.read(empty, "$.pagination.has_next")).isFalse();
        var older = order(mine, Order.Side.SELL, true);
        executions.save(new Execution(older, 100, 10, BigDecimal.ZERO, BigDecimal.ZERO, TIME));
        var newer = order(mine, Order.Side.SELL, true);
        executions.save(new Execution(newer, 100, 10, BigDecimal.ZERO, BigDecimal.ZERO, TIME.plusSeconds(1)));
        String first = get(path(mine) + "?order_side=sell&limit=1", owner, 200);
        assertThat(((Number) JsonPath.read(first, "$.executions[0].order_id")).longValue()).isEqualTo(newer.getId());
        String cursor = JsonPath.read(first, "$.pagination.next_cursor");
        String last = get(path(mine) + "?order_side=sell&limit=1&cursor=" + cursor, owner, 200);
        assertThat(((Number) JsonPath.read(last, "$.executions[0].order_id")).longValue()).isEqualTo(older.getId());
        assertThat((Boolean) JsonPath.read(last, "$.pagination.has_next")).isFalse();
        assertThat((List<?>) JsonPath.read(get(path(mine) + "?order_side=buy", owner, 200), "$.executions")).isEmpty();
    }

    @Test
    void supportsAllRowsOrLimitedPagesAndRejectsInvalidPagination() throws Exception {
        for (int i = 0; i < 11; i++) {
            var o = order(mine, Order.Side.BUY, true);
            executions.save(new Execution(o, 100, 10, null, null, TIME.plusSeconds(i)));
        }
        String all = get(path(mine), owner, 200);
        assertThat((List<?>) JsonPath.read(all, "$.executions")).hasSize(11);
        assertThat(JsonPath.<Map<String, Object>>read(all, "$")).doesNotContainKey("pagination");
        String page = get(path(mine) + "?limit=10", owner, 200);
        assertThat((List<?>) JsonPath.read(page, "$.executions")).hasSize(10);
        assertThat((Boolean) JsonPath.read(page, "$.pagination.has_next")).isTrue();
        String exact = get(path(mine) + "?limit=11", owner, 200);
        assertThat((Boolean) JsonPath.read(exact, "$.pagination.has_next")).isFalse();
        assertThat((List<?>) JsonPath.read(get(path(mine) + "?limit=100", owner, 200), "$.executions")).hasSize(11);
        for (String limit : List.of("0", "-1", "101", "abc", "2147483648")) {
            get(path(mine) + "?limit=" + limit, owner, 400);
        }
        for (String cursor : List.of("", "invalid!", "a".repeat(513))) {
            String failure = get(path(mine) + "?cursor=" + cursor, owner, 400);
            assertThat((String) JsonPath.read(failure, "$.code")).isEqualTo("INVALID_TRADE_HISTORY_CURSOR");
        }
    }

    @Test
    void emptyPagedHistoryReturnsLastPageMetadataAndPreservesAccessChecks() throws Exception {
        String empty = get(path(mine) + "?limit=10", owner, 200);
        assertThat((List<?>) JsonPath.read(empty, "$.executions")).isEmpty();
        assertThat((Boolean) JsonPath.read(empty, "$.pagination.has_next")).isFalse();
        assertThat((Object) JsonPath.read(empty, "$.pagination.next_cursor")).isNull();
        get(path(mine) + "?limit=10", null, 401);
        get(path(mine) + "?limit=10", users.save(new User("페이지 타인")), 404);
    }

    @Test
    void continuesWhenCursorOrderIsDeletedAndRejectsMissingExecutionData() throws Exception {
        var older = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(older, 100, 10, null, null, TIME));
        var newer = order(mine, Order.Side.BUY, true);
        var fill = executions.save(new Execution(newer, 100, 10, null, null, TIME.plusSeconds(1)));
        String first = get(path(mine) + "?limit=1", owner, 200);
        String cursor = JsonPath.read(first, "$.pagination.next_cursor");
        executions.deleteById(fill.getId());
        orders.deleteById(newer.getId());
        String next = get(path(mine) + "?limit=1&cursor=" + cursor, owner, 200);
        assertThat(((Number) JsonPath.read(next, "$.executions[0].order_id")).longValue()).isEqualTo(older.getId());
        order(mine, Order.Side.BUY, true);
        assertThat((String) JsonPath.read(get(path(mine), owner, 500), "$.code")).isEqualTo("INVALID_ORDER_DATA");
    }

    @Test
    void combinesPriceLevelsIntoOneOrderCardAndExcludesPendingCancelledOrders() throws Exception {
        var buy = order(mine, Order.Side.BUY, true);
        executions.save(new Execution(buy, 100, 4, null, null, TIME));
        executions.save(new Execution(buy, 110, 6, null, null, TIME));
        var sell = order(mine, Order.Side.SELL, true);
        executions.save(new Execution(sell, 120, 4, new BigDecimal("80"), new BigDecimal("20"), TIME.plusSeconds(60)));
        executions.save(new Execution(sell, 130, 6, new BigDecimal("120"), new BigDecimal("18.1818"), TIME.plusSeconds(60)));
        order(mine, Order.Side.BUY, false);
        var cancelled = order(mine, Order.Side.BUY, false);
        cancelled.cancel(TIME);
        orders.save(cancelled);
        var body = get(path(mine), owner, 200);
        List<Map<String, Object>> rows = JsonPath.read(body, "$.executions");
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.getFirst().get("order_id")).longValue()).isEqualTo(sell.getId());
        assertThat(rows.getFirst()).containsEntry("execution_quantity", 10).containsEntry("execution_amount", 1260)
                .containsEntry("realized_profit_amount", 200.0).containsEntry("realized_return_percent", 18.8679)
                .containsEntry("executed_at", "2026-10-16T15:01:00+09:00").containsEntry("stock_name", "삼성전자")
                .doesNotContainKeys("execution_id", "reason", "order_status", "execution_summary");
        assertThat(rows.get(1)).containsEntry("execution_amount", 1060).containsEntry("realized_profit_amount", null)
                .containsEntry("realized_return_percent", null);
    }

    @Test
    void blocksAiHistoryAndReportUntilCompletionIncludingExistingOrderEndpoint() throws Exception {
        var order = order(ai, Order.Side.BUY, true);
        executions.save(new Execution(order, 100, 10, null, null, TIME));
        get(path(ai), owner, 403);
        get("/api/v1/accounts/" + ai.getId() + "/orders", owner, 403);
        get(reportPath(ai, order.getId()), owner, 403);
        competition.complete(Competition.Winner.DRAW, TIME.plusSeconds(3600));
        competitions.save(competition);
        // 알고 있는 참가 계좌 ID → 매매 내역의 주문 ID → 기존 판단 근거 상세 API.
        var history = get(path(ai), owner, 200);
        Number orderId = JsonPath.read(history, "$.executions[0].order_id");
        var report = get(reportPath(ai, orderId.longValue()), owner, 200);
        assertThat((String) JsonPath.read(report, "$.reason")).isEqualTo("실적 개선");
    }

    @Test
    void rejectsUnknownOrdinaryAndOtherOwnersAccountsAndAllowsEmptyUserHistory() throws Exception {
        assertThat((List<?>) JsonPath.read(get(path(mine), owner, 200), "$.executions")).isEmpty();
        get(path(mine), null, 401);
        get(path(mine), users.save(new User("타인")), 404);
        get("/api/v2/accounts/9223372036854775807/executions", owner, 404);
        var ordinary = accounts.save(new Account(owner, "일반", 10_000_000, false));
        get(path(ordinary), owner, 404);
    }

    @ParameterizedTest
    @EnumSource(value = Competition.State.class, names = {"READY", "CANCELLED"})
    void keepsAiHistoryPrivateForOtherUncompletedStates(Competition.State state) throws Exception {
        ReflectionTestUtils.setField(competition, "state", state);
        competitions.save(competition);
        get(path(ai), owner, 403);
        get("/api/v1/accounts/" + ai.getId() + "/orders", owner, 403);
        get(reportPath(ai, Long.MAX_VALUE), owner, 403);
    }

    private Order order(Account account, Order.Side side, boolean executed) {
        var order = Order.pendingLimit(account, "005930", side, 10, 100,
                account.isAiManaged() ? Order.Source.AI : Order.Source.USER,
                account.isAiManaged() ? "실적 개선" : null, "삼성전자", List.of(), TIME);
        if (executed) order.execute();
        return orders.save(order);
    }
    private String path(Account account) { return "/api/v2/accounts/" + account.getId() + "/executions"; }
    private String reportPath(Account account, long orderId) {
        return "/api/v1/accounts/" + account.getId() + "/orders/" + orderId + "/ai-report";
    }
    private String get(String path, User user, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
        if (user != null) request.header("Cookie", "access_token=" + tokens.issue(user.getId()).accessToken());
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(expected);
        return response.body();
    }
}
