package com.stock_spoon.river_be.user.controller;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.auth.entity.RefreshToken;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.order.entity.*;
import com.stock_spoon.river_be.report.entity.ReportReasoning;
import com.stock_spoon.river_be.user.entity.*;
import com.stock_spoon.river_be.watchlist.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// [회원 탈퇴 테스트 추가] 실제 보안 필터·JPA·트랜잭션을 사용하고 외부 카카오 호출만 대체한다.
@SpringBootTest(properties = {"kakao.client-id=test", "kakao.redirect-uri=http://localhost:3000/callback"})
class UserWithdrawalTests {
    @Autowired WebApplicationContext context;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean KakaoClient kakao;
    @Autowired com.stock_spoon.river_be.user.service.UserWithdrawalService withdrawal;
    @Autowired com.stock_spoon.river_be.auth.service.AuthService auth;
    @Autowired com.stock_spoon.river_be.auth.service.RefreshTokenService refreshTokens;
    @Autowired com.stock_spoon.river_be.execution.service.OrderExecutionService execution;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionCompletionService completion;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionDailyPerformanceService performances;
    @Autowired com.stock_spoon.river_be.account.service.AccountService accountService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.stock_spoon.river_be.user.repository.UserDeletionRepository deletion;
    MockMvc mvc;
    long userId;
    String access;
    // 전체 삭제 범위의 행 수를 비교한다. stocks는 공용 데이터라 삭제 목록에서 제외하고 별도로 보존을 검사한다.
    static final List<String> TABLES = List.of("users", "user_oauth", "accounts", "holdings",
            "trade_orders", "executions", "ai_order_reports", "ai_report_reasoning", "competitions",
            "competition_participants", "competition_daily_performances", "competition_market_analyses",
            "competition_judgment_summaries", "refresh_token", "watchlist_stocks", "order_request_records");

    // 매 테스트마다 연관 데이터가 있는 새 회원과 인증 쿠키를 준비한다. 요청 자체가 커밋하도록 테스트 전체를 트랜잭션으로 감싸지 않는다.
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        userId = new TransactionTemplate(manager).execute(s -> fixture());
        access = tokens.issue(userId).accessToken();
    }

    // 일반·AI·비활성 계좌, 대기·체결 주문, 리포트, 대결 성과, 여러 Refresh Token과 관심 등록을 함께 저장한다.
    long fixture() {
        var now = Instant.now();
        var user = new User("탈퇴 테스트"); em.persist(user);
        em.persist(new UserOAuth(user, OAuthProvider.KAKAO, user.getId() + 100000));
        var account = new Account(user, "일반", 100000, false); em.persist(account);
        var ai = new Account(user, "AI", 100000, true); em.persist(ai);
        var inactive = new Account(user, "비활성", 100000, false); em.persist(inactive);
        em.flush();
        em.createNativeQuery("update accounts set is_active=false where account_id=:id")
                .setParameter("id", inactive.getId()).executeUpdate();
        var order = Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 10000,
                Order.Source.AI, "판단", "종목", List.of(new ReportReasoning("근거", "내용")), now);
        em.persist(order);
        var executed = Order.pendingMarket(ai, "005930", Order.Side.BUY, 1, "판단", now);
        executed.execute(); em.persist(executed);
        em.persist(new Execution(executed, 10000, 1, null, null, now));
        em.persist(new Holding(ai, "005930", 1, BigDecimal.valueOf(10000)));
        var competition = new Competition(user, "대결", Competition.Type.USER_VS_AI,
                LocalDate.now(), LocalDate.now().plusDays(1), 100000, 1); em.persist(competition);
        var participant = new CompetitionParticipant(competition, ai); em.persist(participant);
        em.persist(new CompetitionDailyPerformance(participant, LocalDate.now(), 100000));
        em.persist(new CompetitionMarketAnalysis(competition, LocalDate.now(), "분석", "내용"));
        em.persist(new CompetitionJudgmentSummary(competition, "판정", "내용"));
        em.persist(new RefreshToken(user, "hash-" + user.getId(), now.plusSeconds(3600)));
        em.persist(new RefreshToken(user, "second-" + user.getId(), now.plusSeconds(3600)));
        var stock = new Stock("T" + user.getId(), "종목", "KOSPI", null); em.persist(stock);
        em.persist(new Watchlist(user.getId(), stock));
        em.persist(new OrderRequestRecord("event-" + user.getId(), account.getId(), user.getId(),
                "hash", order.getId(), null, now));
        return user.getId();
    }

    // [회원 탈퇴 테스트 추가] 전체 소유 데이터 삭제, 타 회원·공용 종목 보존, 쿠키 만료 및 이전 토큰 거절을 확인한다.
    @Test void deletesAllOwnedDataAndKeepsOtherUsersAndStocks() throws Exception {
        var before = TABLES.stream().map(t -> count(t)).toList();
        long otherId = new TransactionTemplate(manager).execute(s -> fixture());
        long stocks = count("stocks");
        var response = withdraw().andExpect(status().isNoContent()).andReturn().getResponse();
        for (int i = 0; i < TABLES.size(); i++) assertThat(count(TABLES.get(i))).as(TABLES.get(i)).isEqualTo(before.get(i));
        assertThat(count("stocks")).isEqualTo(stocks);
        assertThat(jdbc.queryForObject("select count(*) from users where user_id=?", Long.class, otherId)).isEqualTo(1);
        assertThat(response.getCookie("access_token").getMaxAge()).isZero();
        assertThat(response.getCookie("refresh_token").getMaxAge()).isZero();
        mvc.perform(get("/api/v1/users/me").cookie(new Cookie("access_token", access)))
                .andExpect(status().isUnauthorized());
    }

    // [회원 탈퇴 테스트 추가] Access Token이 있어도 CSRF가 없으면 탈퇴를 거절하고 회원 데이터를 유지하는지 확인한다.
    @Test void requiresCsrfAndAuthentication() throws Exception {
        mvc.perform(delete("/api/v1/users/me").cookie(new Cookie("access_token", access)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from users where user_id=?", Long.class, userId)).isEqualTo(1);
    }

    // [회원 탈퇴 테스트 추가] 카카오 호출이 실패하면 모든 DB 데이터와 로그인 쿠키를 보존하는지 확인한다.
    @Test void providerFailurePreservesAllDataAndCookies() throws Exception {
        var before = TABLES.stream().map(this::count).toList();
        org.mockito.Mockito.doThrow(new com.stock_spoon.river_be.auth.exception.AuthException(
                org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "OAUTH_PROVIDER_TIMEOUT", "timeout"))
                .when(kakao).unlink(userId + 100000);
        var response = withdraw().andExpect(status().isGatewayTimeout()).andReturn().getResponse();
        assertThat(TABLES.stream().map(this::count).toList()).isEqualTo(before);
        assertThat(response.getCookie("access_token")).isNull();
        assertThat(response.getCookie("refresh_token")).isNull();
    }

    // [회원 탈퇴 테스트 추가] 일부 SQL 삭제 후 오류를 주입해 같은 트랜잭션의 선행 삭제까지 롤백되는지 확인한다.
    @Test void databaseFailureRollsBackEarlierDeletes() {
        var before = TABLES.stream().map(this::count).toList();
        org.mockito.Mockito.doAnswer(call -> {
            em.createNativeQuery("delete from holdings where account_id in (select account_id from accounts where user_id=:id)")
                    .setParameter("id", userId).executeUpdate();
            throw new IllegalStateException("database deletion failed");
        }).when(deletion).deleteAllForUser(userId);
        assertThatThrownBy(() -> withdrawal.withdraw(userId)).hasMessage("database deletion failed");
        assertThat(TABLES.stream().map(this::count).toList()).isEqualTo(before);
        org.mockito.Mockito.verify(kakao).unlink(userId + 100000);
    }

    // [회원 탈퇴 테스트 추가] 이전 토큰은 거절하고 재가입은 새 회원·미완료 온보딩으로 시작하며 오래된 탈퇴 요청이 새 연결을 끊지 않는지 확인한다.
    @Test void rejoiningCreatesNewUserAndOldRequestsCannotDeleteThem() throws Exception {
        var original = new TransactionTemplate(manager).execute(s -> em.find(User.class, userId));
        var oldTokens = refreshTokens.create(original);
        withdraw().andExpect(status().isNoContent());
        assertThatThrownBy(() -> auth.reissue(oldTokens.refreshToken()))
                .isInstanceOf(com.stock_spoon.river_be.auth.exception.AuthException.class);
        assertThatThrownBy(() -> refreshTokens.create(original))
                .isInstanceOf(com.stock_spoon.river_be.auth.exception.AuthException.class);
        org.mockito.Mockito.when(kakao.verifyUser("rejoin")).thenReturn(
                new com.stock_spoon.river_be.auth.client.KakaoUserInfo(userId + 100000, "재가입", null));
        long newId = auth.login("rejoin").userId();
        assertThat(newId).isNotEqualTo(userId);
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            assertThat(em.find(User.class, newId).isOnboardingCompleted()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from accounts where user_id=?", Long.class, newId)).isZero();
        });
        assertThatThrownBy(() -> withdrawal.withdraw(userId))
                .isInstanceOf(com.stock_spoon.river_be.user.exception.UserNotFoundException.class);
        org.mockito.Mockito.verify(kakao, org.mockito.Mockito.times(1)).unlink(userId + 100000);
    }

    // [회원 탈퇴 테스트 추가] 탈퇴 후 뒤늦게 실행되는 체결·대결 정산·성과 작업이 데이터 재생성 없이 종료되는지 확인한다.
    @Test void delayedExecutionAndCompetitionJobsSkipDeletedTargets() throws Exception {
        long competitionId = jdbc.queryForObject("select competition_id from competitions where user_id=?", Long.class, userId);
        long accountId = jdbc.queryForObject("select account_id from accounts where user_id=? and name='일반'", Long.class, userId);
        long orderId = jdbc.queryForObject("select order_id from trade_orders where account_id=?", Long.class, accountId);
        withdraw().andExpect(status().isNoContent());
        assertThat(execution.executeLimit(accountId, orderId, 10000)).isFalse();
        assertThatCode(() -> completion.completeCompetition(competitionId)).doesNotThrowAnyException();
        assertThatCode(() -> performances.recordMissingDays(competitionId)).doesNotThrowAnyException();
    }

    // [회원 탈퇴 테스트 추가] 탈퇴가 회원 잠금을 가진 동안 계좌 생성을 시작해 삭제된 회원의 계좌가 남지 않는지 확인한다.
    @Test void accountCreationWaitingBehindWithdrawalCannotCreateAnOrphan() throws Exception {
        new TransactionTemplate(manager).executeWithoutResult(s -> em.find(User.class, userId).completeOnboarding());
        var unlinkStarted = new java.util.concurrent.CountDownLatch(1);
        var finishUnlink = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            unlinkStarted.countDown();
            if (!finishUnlink.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return null;
        }).when(kakao).unlink(userId + 100000);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var deleting = pool.submit(() -> withdrawal.withdraw(userId));
            try {
                assertThat(unlinkStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var creating = pool.submit(() -> {
                    assertThatThrownBy(() -> accountService.create(userId,
                            new com.stock_spoon.river_be.account.dto.AccountCreateRequest("새 계좌", 1000000L)))
                            .isInstanceOf(com.stock_spoon.river_be.account.exception.AccountException.class);
                });
                finishUnlink.countDown();
                deleting.get(10, java.util.concurrent.TimeUnit.SECONDS);
                creating.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("select count(*) from accounts where user_id=?", Long.class, userId)).isZero();
            } finally { finishUnlink.countDown(); }
        }
    }

    // 실제 CSRF 발급 API에서 받은 쿠키·헤더와 회원 Access Token으로 탈퇴 HTTP 요청을 보낸다.
    org.springframework.test.web.servlet.ResultActions withdraw() throws Exception {
        var csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        String token = com.jayway.jsonpath.JsonPath.read(csrf.getContentAsString(), "$.token");
        return mvc.perform(delete("/api/v1/users/me")
                .cookie(new Cookie("access_token", access), csrf.getCookie("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", token));
    }

    // 테스트 내부의 고정 테이블 목록만 사용해 커밋된 삭제 결과와 실패 시 롤백 결과를 비교한다.
    long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
}
