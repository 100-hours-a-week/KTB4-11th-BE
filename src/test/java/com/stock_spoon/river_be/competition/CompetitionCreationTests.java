package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.service.CompetitionService;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.competition.service.CompetitionStartScheduler;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class CompetitionCreationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stock_spoon.river_be.market.RedisMarketPrice redisPrices;
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @MockitoSpyBean CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @MockitoSpyBean CompetitionTradingHours tradingHours;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.stock_spoon.river_be.account.service.AccountService accountService;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired com.stock_spoon.river_be.auth.token.JwtTokenProvider tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient market;
    @Autowired com.stock_spoon.river_be.holding.repository.HoldingRepository holdings;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionCompletionService completion;
    @Autowired com.stock_spoon.river_be.order.repository.OrderRepository orders;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionDailyPerformanceService dailyPerformances;
    private User user;

    @BeforeEach
    void setup() {
        org.mockito.Mockito.doReturn(today().atTime(21, 0).atZone(ZoneId.of("Asia/Seoul")))
                .when(tradingHours).now();
        user = new User("대결" + UUID.randomUUID().toString().substring(0, 4));
        user.completeOnboarding();
        user = users.save(user);
    }

    @Test
    void failedClosingPriceLeavesResultsUnchangedAndSchedulerRetriesAfterRestart() {
        var start = java.time.ZonedDateTime.parse("2026-10-12T10:00:00+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(start).when(tradingHours).now();
        var req = request("정산 재시도", 4, 1);
        ReflectionTestUtils.setField(req, "startDate", start.toLocalDate());
        ReflectionTestUtils.setField(req, "endDate", LocalDate.of(2026, 10, 16));
        var id = service.createCompetition(user.getId(), req).getCompetitionId();
        var account = participants.findAllByCompetitionIdOrderByIdAsc(id).getFirst().getAccount();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            accounts.findById(account.getId()).orElseThrow().changeCash(-1_000_000);
            holdings.save(new com.stock_spoon.river_be.holding.entity.Holding(account, "005930", 10,
                    java.math.BigDecimal.valueOf(1_000_000)));
        });
        org.mockito.Mockito.doReturn(start.plusDays(7)).when(tradingHours).now();
        org.mockito.Mockito.when(market.closingPrice("005930", LocalDate.of(2026, 10, 16)))
                .thenThrow(new IllegalStateException("종가 미제공"));
        assertThatThrownBy(() -> completion.completeCompetition(id)).isInstanceOf(IllegalStateException.class);
        assertThat(competitions.findById(id).orElseThrow().getState().name()).isEqualTo("IN_PROGRESS");
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(id))
                .allSatisfy(p -> assertThat(p.getFinalAssetValue()).isNull());
        org.mockito.Mockito.doReturn(90_000L).when(market).closingPrice("005930", LocalDate.of(2026, 10, 16));
        new com.stock_spoon.river_be.competition.service.CompetitionCompletionScheduler(competitions, completion, tradingHours, dailyPerformances)
                .completeExpiredCompetitions();
        assertThat(competitions.findById(id).orElseThrow().getWinner().name()).isEqualTo("AI");
        assertThat(service.listCompetitions(user.getId(), null, 10, null).getCompetitions().getFirst().getCumulativeReturnPercent()).isEqualTo(-1.0);
    }

    @Test
    void equalFinalAssetsProduceDraw() {
        var start = java.time.ZonedDateTime.parse("2026-10-12T10:00:00+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(start).when(tradingHours).now();
        var req = request("무승부", 4, 1);
        ReflectionTestUtils.setField(req, "startDate", start.toLocalDate());
        ReflectionTestUtils.setField(req, "endDate", LocalDate.of(2026, 10, 16));
        var id = service.createCompetition(user.getId(), req).getCompetitionId();
        org.mockito.Mockito.doReturn(start.plusDays(4).withHour(15).withMinute(30)).when(tradingHours).now();
        completion.completeCompetition(id);
        assertThat(service.listCompetitions(user.getId(), null, 10, null).getCompetitions().getFirst().getWinner()).isEqualTo("draw");
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(id))
                .allSatisfy(p -> assertThat(p.getFinalReturnPercent()).isEqualByComparingTo("0"));
    }

    @Test
    void completesAtFridayCloseForSundayEndAndKeepsHistoricalResult() {
        var start = java.time.ZonedDateTime.parse("2026-10-12T10:00:00+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(start).when(tradingHours).now();
        var req = request("마감 정산", 6, 1);
        ReflectionTestUtils.setField(req, "startDate", start.toLocalDate());
        ReflectionTestUtils.setField(req, "endDate", LocalDate.of(2026, 10, 18));
        var id = service.createCompetition(user.getId(), req).getCompetitionId();
        var links = participants.findAllByCompetitionIdOrderByIdAsc(id);
        var userAccount = links.getFirst().getAccount();
        var aiAccount = links.getLast().getAccount();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            accounts.findById(userAccount.getId()).orElseThrow().changeCash(-1_000_000);
            holdings.save(new com.stock_spoon.river_be.holding.entity.Holding(userAccount, "005930", 10,
                    java.math.BigDecimal.valueOf(1_000_000)));
            orders.save(com.stock_spoon.river_be.order.entity.Order.pendingLimit(aiAccount, "005930",
                    com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1, 100_000,
                    com.stock_spoon.river_be.order.entity.Order.Source.AI, "대기", start.toInstant()));
        });
        var before = java.time.ZonedDateTime.parse("2026-10-16T15:29:59+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(before).when(tradingHours).now();
        completion.completeCompetition(id);
        assertThat(competitions.findById(id).orElseThrow().getState().name()).isEqualTo("IN_PROGRESS");
        var close = before.plusSeconds(1);
        org.mockito.Mockito.doReturn(close).when(tradingHours).now();
        // 정산이 늦어져도 반드시 금요일 종가를 사용한다.
        org.mockito.Mockito.when(market.closingPrice("005930", LocalDate.of(2026, 10, 16))).thenReturn(120_000L);
        Boolean blocked = tx.execute(status -> accounts.hasNonTradingCompetition(aiAccount.getId(), close));
        assertThat(blocked).isTrue();
        // 스케줄러가 아직 완료 처리하지 않았어도 생성·체결 경로에서 마감 시각을 검사한다.
        var orderService = new com.stock_spoon.river_be.order.service.OrderService(accounts, orders, holdings);
        ReflectionTestUtils.setField(orderService, "clock", java.time.Clock.fixed(close.toInstant(), ZoneId.of("Asia/Seoul")));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> orderService.reserveLimit(
                user.getId(), aiAccount.getId(), "005930", com.stock_spoon.river_be.order.entity.Order.Side.BUY,
                1, 100_000, "마감 후 주문")))
                .isInstanceOf(com.stock_spoon.river_be.order.exception.OrderException.class);
        var executionService = new com.stock_spoon.river_be.execution.service.OrderExecutionService(accounts, orders,
                holdings, context.getBean(com.stock_spoon.river_be.execution.repository.ExecutionRepository.class));
        ReflectionTestUtils.setField(executionService, "clock", java.time.Clock.fixed(close.toInstant(), ZoneId.of("Asia/Seoul")));
        var pending = orders.findAllByAccountIdAndStatus(aiAccount.getId(),
                com.stock_spoon.river_be.order.entity.Order.Status.PENDING).getFirst();
        Boolean executed = tx.execute(status -> executionService.executeLimit(aiAccount.getId(), pending.getId(), 90_000));
        assertThat(executed).isFalse();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> executionService.executeMarketBuy(
                user.getId(), aiAccount.getId(), "005930", 1, "마감 후 시장가", null)))
                .isInstanceOf(com.stock_spoon.river_be.order.exception.OrderException.class);
        completion.completeCompetition(id);
        assertThat(competitions.findById(id).orElseThrow().getState().name()).isEqualTo("COMPLETED");
        var result = service.listCompetitions(user.getId(), null, 10, null);
        assertThat(result.getOngoingCompetitionCount()).isZero();
        assertThat(result.getCompetitions().getFirst().getWinner()).isEqualTo("user");
        assertThat(result.getCompetitions().getFirst().getCumulativeReturnPercent()).isEqualTo(2.0);
        var lastPoint = context.getBean(com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository.class)
                .findByParticipantIdAndRecordDate(links.getFirst().getId(), LocalDate.of(2026, 10, 16)).orElseThrow();
        assertThat(lastPoint.getAssetValue()).isEqualTo(10_200_000);
        assertThat(lastPoint.getCumulativeReturnPercent()).isEqualByComparingTo("2.0");
        assertThat(result.getCompetitions().getFirst().getRemainingDays()).isNull();
        assertThat(orders.findAllByAccountIdAndStatus(aiAccount.getId(),
                com.stock_spoon.river_be.order.entity.Order.Status.PENDING)).isEmpty();
        org.mockito.Mockito.doReturn(close.plusDays(3)).when(tradingHours).now();
        completion.completeCompetition(id);
        assertThat(service.listCompetitions(user.getId(), null, 10, null).getCompetitions().getFirst().getCumulativeReturnPercent()).isEqualTo(2.0);
        org.mockito.Mockito.verify(market, org.mockito.Mockito.times(1))
                .closingPrice("005930", LocalDate.of(2026, 10, 16));
    }

    @Test
    void listValuesUserHoldingsAndLeavesUnsettledResultsNull() {
        var first = service.createCompetition(user.getId(), request("수익률", 3, 1));
        var second = service.createCompetition(user.getId(), request("종료", 3, 1));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            var live = competitions.findById(first.getCompetitionId()).orElseThrow();
            live.start(tradingHours.now().toInstant());
            var account = participants.findAllByCompetitionIdOrderByIdAsc(live.getId()).getFirst().getAccount();
            account.changeCash(-1_000_000);
            holdings.save(new com.stock_spoon.river_be.holding.entity.Holding(account, "005930", 10,
                    java.math.BigDecimal.valueOf(1_000_000)));
            ReflectionTestUtils.setField(competitions.findById(second.getCompetitionId()).orElseThrow(),
                    "state", com.stock_spoon.river_be.competition.entity.Competition.State.COMPLETED);
        });
        org.mockito.Mockito.when(redisPrices.currentPrice("005930")).thenReturn(120_000L);
        var response = service.listCompetitions(user.getId(), null, 10, null);
        assertThat(response.getOngoingCompetitionCount()).isEqualTo(1);
        assertThat(response.getCompetitions().get(0).getRemainingDays()).isNull();
        assertThat(response.getCompetitions().get(0).getCumulativeReturnPercent()).isNull();
        assertThat(response.getCompetitions().get(0).getWinner()).isNull();
        assertThat(response.getCompetitions().get(1).getCumulativeReturnPercent()).isEqualTo(2.0);
        org.mockito.Mockito.when(redisPrices.currentPrice("005930")).thenThrow(new IllegalStateException("시세 장애"));
        assertThatThrownBy(() -> service.listCompetitions(user.getId(), null, 10, null))
                .isInstanceOf(com.stock_spoon.river_be.account.exception.AccountException.class);
    }

    @Test
    void listReturnsOnlyOwnedCompetitionsNewestFirstWithExplicitPagination() throws Exception {
        var other = new User("다른" + UUID.randomUUID().toString().substring(0, 4));
        other.completeOnboarding();
        other = users.save(other);
        service.createCompetition(other.getId(), request("다른 사용자 대결", 3, 1));
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 12; i++) {
            ids.add(service.createCompetition(user.getId(), request("목록" + i, 3, 1)).getCompetitionId());
        }
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = new jakarta.servlet.http.Cookie("access_token", tokens.issue(user.getId()).accessToken());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/users/me/competitions").cookie(auth).param("limit", "10"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ongoing_competition_count").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions.length()").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pagination.limit").value(10))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.pagination.has_next").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions[0].competition_id").value(ids.getLast().intValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions[9].competition_id").value(ids.get(2).intValue()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions[0].d_day").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions[0].cumulative_return_percent").value(0.0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions[0].winner").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void listReturnsEmptyArrayForUserWithoutCompetitions() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/users/me/competitions")
                        .cookie(new jakarta.servlet.http.Cookie("access_token", tokens.issue(user.getId()).accessToken())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ongoing_competition_count").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competitions").isEmpty());
    }

    @Test
    void createsTwoAccountsAndParticipantLinksWithEqualCapital() {
        var response = service.createCompetition(user.getId(), request("반도체 집중", 3, 1));
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getState()).isEqualTo("ready");
        var links = participants.findAllByCompetitionIdOrderByIdAsc(response.getCompetitionId());
        assertThat(links).hasSize(2);
        assertThat(links.get(0).getAccount().getName()).isEqualTo("반도체 집중");
        assertThat(links.get(0).getAccount().isAiManaged()).isFalse();
        assertThat(links.get(1).getAccount().isAiManaged()).isTrue();
        assertThat(links.get(1).getAccount().getName()).isNotEqualTo("반도체 집중");
        assertThat(links).allSatisfy(p -> assertThat(p.getAccount().getCashBalance()).isEqualTo(10_000_000));
        assertThat(accounts.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(user.getId()))
                .extracting(Account::getId).containsExactly(links.get(0).getAccount().getId());
        assertThat(accounts.findActiveAiManaged()).extracting(Account::getId)
                .doesNotContain(links.get(1).getAccount().getId());
        assertThat(accountService.list(user.getId())).singleElement()
                .satisfies(a -> assertThat(a.duelAccount()).isTrue());
        assertThatThrownBy(() -> accountService.create(user.getId(),
                new com.stock_spoon.river_be.account.dto.AccountCreateRequest("반도체 집중", 10_000_000L)))
                .isInstanceOf(com.stock_spoon.river_be.account.exception.AccountException.class);
        assertThatThrownBy(() -> context.getBean(com.stock_spoon.river_be.order.service.OrderService.class)
                .assertOrderableAccount(user.getId(), links.get(1).getAccount().getId()))
                .isInstanceOf(com.stock_spoon.river_be.order.exception.OrderException.class);
    }

    @Test
    void apiMapsSnakeCaseAndReturnsCreatedAndValidationErrors() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        String body = """
                {"competition_name":"API 생성","competition_type":"user_vs_ai",
                 "start_date":"%s","end_date":"%s","initial_capital":10000000,"ai_participant_count":1}
                """.formatted(today(), today().plusDays(3));
        var auth = new jakarta.servlet.http.Cookie("access_token", tokens.issue(user.getId()).accessToken());
        var csrfResponse = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf"))
                .andReturn().getResponse();
        var csrf = csrfResponse.getCookie("XSRF-TOKEN");
        String token = com.jayway.jsonpath.JsonPath.read(csrfResponse.getContentAsString(), "$.token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/users/me/competitions")
                .cookie(auth, csrf).header("X-XSRF-TOKEN", token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.competition_id").isNumber())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("ready"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/users/me/competitions")
                .cookie(auth, csrf).header("X-XSRF-TOKEN", token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DUPLICATE_ACCOUNT_NAME"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/users/me/competitions")
                .cookie(auth, csrf).header("X-XSRF-TOKEN", token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.replace("10000000", "0")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void startsWaitingCompetitionWhenTheNextMarketSessionOpens() {
        // 토요일 생성 후 월요일 09:00에 시작하고, 요청받은 화요일 종료일은 유지한다.
        var saturday = java.time.ZonedDateTime.parse("2026-10-17T12:00:00+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(saturday).when(tradingHours).now();
        var request = request("주말대기", 3, 1);
        ReflectionTestUtils.setField(request, "startDate", saturday.toLocalDate());
        ReflectionTestUtils.setField(request, "endDate", saturday.toLocalDate().plusDays(3));
        var response = service.createCompetition(user.getId(), request);
        assertThat(response.getState()).isEqualTo("ready");
        var monday = saturday.plusDays(2).withHour(9).withMinute(0);
        org.mockito.Mockito.doReturn(monday).when(tradingHours).now();
        var scheduler = new CompetitionStartScheduler(competitions, tradingHours);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> scheduler.startWaitingCompetitions());
        // 같은 처리를 반복해도 이미 시작한 대결은 다시 시작하지 않는다.
        tx.executeWithoutResult(status -> scheduler.startWaitingCompetitions());
        var saved = competitions.findById(response.getCompetitionId()).orElseThrow();
        assertThat(saved.getState()).isEqualTo(com.stock_spoon.river_be.competition.entity.Competition.State.IN_PROGRESS);
        assertThat(saved.getStartedAt()).isEqualTo(monday.toInstant());
        assertThat(saved.getEndDate()).isEqualTo(saturday.toLocalDate().plusDays(3));
    }

    @Test
    void startsImmediatelyDuringMarketHours() {
        var now = java.time.ZonedDateTime.parse("2026-10-16T09:00:00+09:00[Asia/Seoul]");
        org.mockito.Mockito.doReturn(now).when(tradingHours).now();
        var request = request("개장", 3, 1);
        ReflectionTestUtils.setField(request, "startDate", now.toLocalDate());
        ReflectionTestUtils.setField(request, "endDate", now.toLocalDate().plusDays(3));
        var response = service.createCompetition(user.getId(), request);
        assertThat(response.getState()).isEqualTo("in_progress");
        assertThat(accounts.findActiveAiManagedOn(now.toLocalDate())).extracting(Account::getId)
                .contains(participants.findAllByCompetitionIdOrderByIdAsc(response.getCompetitionId()).get(1).getAccount().getId());
    }

    @Test
    void rejectsShortPeriodsWrongStartAndUnsupportedParticipantCounts() {
        for (int days : new int[]{-1, 0, 1, 2}) {
            assertThatThrownBy(() -> service.createCompetition(user.getId(), request("기간", days, 1)))
                    .isInstanceOf(CompetitionException.class);
        }
        var past = request("과거", 3, 1);
        ReflectionTestUtils.setField(past, "startDate", today().minusDays(1));
        assertThatThrownBy(() -> service.createCompetition(user.getId(), past)).isInstanceOf(CompetitionException.class);
        assertThatThrownBy(() -> service.createCompetition(user.getId(), request("인원", 3, 2)))
                .isInstanceOf(CompetitionException.class);
    }

    @Test
    void rejectsNameUsedByAnExistingAccount() {
        accounts.save(new Account(user, "기존 계좌", 10_000_000));
        assertThatThrownBy(() -> service.createCompetition(user.getId(), request(" 기존 계좌 ", 3, 1)))
                .isInstanceOf(CompetitionException.class)
                .hasMessageContaining("이름");
    }

    @Test
    void concurrentRequestsWithTheSameNameCreateOnlyOneCompetition() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> create = () -> {
            start.await();
            try {
                service.createCompetition(user.getId(), request("동시생성", 3, 1));
                return true;
            } catch (CompetitionException expected) {
                assertThat(expected.getCode()).isEqualTo("DUPLICATE_ACCOUNT_NAME");
                return false;
            }
        };
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(accounts.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(user.getId())).hasSize(1);
    }

    @Test
    void rollsBackCompetitionAndBothAccountsWhenParticipantSaveFails() {
        long competitionCount = competitions.count();
        long accountCount = accounts.count();
        long participantCount = participants.count();
        doThrow(new IllegalStateException("저장 실패 테스트"))
                .when(participants).save(any(CompetitionParticipant.class));
        assertThatThrownBy(() -> service.createCompetition(user.getId(), request("롤백", 3, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(competitions.count()).isEqualTo(competitionCount);
        assertThat(accounts.count()).isEqualTo(accountCount);
        assertThat(participants.count()).isEqualTo(participantCount);
    }

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }

    private CompetitionCreateRequest request(String name, int days, int count) {
        var request = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(request, "competitionName", name);
        ReflectionTestUtils.setField(request, "competitionType", "user_vs_ai");
        ReflectionTestUtils.setField(request, "startDate", today());
        ReflectionTestUtils.setField(request, "endDate", today().plusDays(days));
        ReflectionTestUtils.setField(request, "initialCapital", 10_000_000L);
        ReflectionTestUtils.setField(request, "aiParticipantCount", count);
        return request;
    }
}
