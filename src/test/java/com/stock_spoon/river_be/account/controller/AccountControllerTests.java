package com.stock_spoon.river_be.account.controller;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.service.OrderService;

import com.jayway.jsonpath.JsonPath;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class AccountControllerTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stock_spoon.river_be.auth.service.JwtUserExistenceVerifier authLookup;
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired JwtTokenProvider tokens;
    @Autowired com.stock_spoon.river_be.order.service.OrderService orders;
    @Autowired jakarta.persistence.EntityManager em;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        // Controller fixtures are uncommitted; real isolated auth retries have their own integration tests.
        org.mockito.Mockito.when(authLookup.exists(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> users.existsById(call.getArgument(0, Long.class)));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void onboardingCreatesTheDefaultAccountAndCompletesOnboarding() throws Exception {
        User user = users.save(new User("계좌사용자"));

        mvc.perform(get("/api/v1/users/me").cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_completed").value(false));

        onboard(user, 10_000_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account_name").value("기본 계좌"))
                .andExpect(jsonPath("$.initial_capital").value(10_000_000))
                .andExpect(jsonPath("$.cash_balance").value(10_000_000))
                .andExpect(jsonPath("$.is_ai_managed").value(false));

        users.flush();
        mvc.perform(get("/api/v1/users/me").cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_completed").value(true));
        assertThat(users.findById(user.getId()).orElseThrow().isOnboardingCompleted()).isTrue();
        assertThat(accounts.findAll()).singleElement().satisfies(account -> {
            assertThat(account.isActive()).isTrue();
            assertThat(account.isAiManaged()).isFalse();
            assertThat(account.getCashBalance()).isEqualTo(account.getInitialCapital());
        });
    }

    @Test
    void completedOnboardingCannotRunAgain() throws Exception {
        User user = users.save(new User("계좌사용자"));
        onboard(user, 10_000_000).andExpect(status().isCreated());

        onboard(user, 10_000_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    }

    @Test
    void additionalAccountRequiresCompletedOnboarding() throws Exception {
        User user = users.save(new User("계좌사용자"));

        createAccount(user, "{\"account_name\":\"투자 계좌\",\"initial_capital\":5000000}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    void missingAdditionalAccountNameUsesTheSmallestAvailableNumber() throws Exception {
        User user = users.save(new User("계좌사용자"));
        onboard(user, 10_000_000).andExpect(status().isCreated());

        createAccount(user, "{\"initial_capital\":5000000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.account_id").isNumber())
                .andExpect(jsonPath("$.initial_capital").value(5_000_000))
                .andExpect(jsonPath("$.cash_balance").value(5_000_000))
                .andExpect(jsonPath("$.is_ai_managed").value(false))
                .andExpect(jsonPath("$.ai_delegated").doesNotExist())
                .andExpect(jsonPath("$.account_name").value("기본 계좌 1"));
        createAccount(user,
                "{\"account_name\":\"기본 계좌 3\",\"initial_capital\":5000000}")
                .andExpect(status().isCreated());
        createAccount(user, "{\"account_name\":\"  \",\"initial_capital\":5000000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account_name").value("기본 계좌 2"));
    }

    @Test
    void activeAccountNamesMustBeUniqueForTheSameUser() throws Exception {
        User user = users.save(new User("계좌사용자"));
        onboard(user, 10_000_000).andExpect(status().isCreated());
        String request = "{\"account_name\":\"투자 계좌\",\"initial_capital\":5000000}";

        createAccount(user, request).andExpect(status().isCreated());
        createAccount(user, request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT_NAME"));
    }

    @Test
    void accountNameCanBeChanged() throws Exception {
        User user = users.save(new User("계좌사용자"));
        long accountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        renameAccount(user, accountId, "  장기 투자  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_id").value(accountId))
                .andExpect(jsonPath("$.account_name").value("장기 투자"));

        assertThat(accounts.findById(accountId).orElseThrow().getName()).isEqualTo("장기 투자");
    }

    @Test
    void accountNameMustNotBeBlankOrLongerThanTwentyCharacters() throws Exception {
        User user = users.save(new User("계좌사용자"));
        long accountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        renameAccount(user, accountId, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_NAME"));
        renameAccount(user, accountId, "123456789012345678901")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_NAME"));
    }

    @Test
    void accountNamesAreComparedWithoutEnglishCaseDifferences() throws Exception {
        User user = users.save(new User("계좌사용자"));
        long defaultAccountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        createAccount(user, "{\"account_name\":\"Stock\",\"initial_capital\":5000000}")
                .andExpect(status().isCreated());

        renameAccount(user, defaultAccountId, "stock")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT_NAME"));
    }

    @Test
    void anotherUsersAccountCannotBeRenamed() throws Exception {
        User owner = users.save(new User("계좌 소유자"));
        User other = users.save(new User("다른 사용자"));
        long accountId = accountId(onboard(owner, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        onboard(other, 10_000_000).andExpect(status().isCreated());

        renameAccount(other, accountId, "가져온 계좌")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void accountListContainsOnlyTheUsersAccountsInCreationOrder() throws Exception {
        User user = users.save(new User("계좌사용자"));
        User other = users.save(new User("다른 사용자"));
        long firstAccountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long secondAccountId = accountId(createAccount(user,
                "{\"account_name\":\"두 번째 계좌\",\"initial_capital\":5000000}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        onboard(other, 10_000_000).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/users/me/accounts").cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account_id").value(firstAccountId))
                .andExpect(jsonPath("$[0].account_name").value("기본 계좌"))
                .andExpect(jsonPath("$[0].length()").value(7))
                .andExpect(jsonPath("$[0].available_cash").value(10_000_000))
                .andExpect(jsonPath("$[0].is_duel_account").value(false))
                .andExpect(jsonPath("$[0].cash_balance").value(10_000_000))
                .andExpect(jsonPath("$[0].total_assets").value(10_000_000))
                .andExpect(jsonPath("$[0].return_percent").value(0.0))
                .andExpect(jsonPath("$[1].account_id").value(secondAccountId))
                .andExpect(jsonPath("$[1].account_name").value("두 번째 계좌"))
                .andExpect(jsonPath("$[1].is_duel_account").value(false))
                .andExpect(jsonPath("$[1].cash_balance").value(5_000_000))
                .andExpect(jsonPath("$[1].total_assets").value(5_000_000))
                .andExpect(jsonPath("$[1].return_percent").value(0.0))
                .andExpect(jsonPath("$[2]").doesNotExist());
    }

    @Test
    void accountListIsEmptyWhenTheUserHasNoAccount() throws Exception {
        User user = users.save(new User("계좌없는사용자"));

        mvc.perform(get("/api/v1/users/me/accounts").cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void accountListRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/users/me/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountDetailReturnsCompletedOrderCount() throws Exception {
        User user = users.save(new User("거래 횟수 사용자"));
        var account = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "집계 계좌", 1000000));
        var completed = orders.reserveLimit(account.getId(), "005930",
                com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1, 100, null);
        completed.execute();
        orders.reserveLimit(account.getId(), "005930",
                com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1, 100, null);
        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", account.getId()).cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed_trade_count").value(1));
    }
    @Test
    void accountDetailReturnsTheUsersActiveAccount() throws Exception {
        User user = users.save(new User("계좌사용자"));
        long accountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        renameAccount(user, accountId, "변경된 계좌")
                .andExpect(status().isOk());
        orders.reserveLimit(user.getId(), accountId,
                new com.stock_spoon.river_be.order.dto.OrderCreateRequest(
                        "005930", "buy", "limit", 70_000L, 10L, null, null, null, null, null),
                Order.Source.USER);

        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", accountId)
                        .cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_id").value(accountId))
                .andExpect(jsonPath("$.account_name").value("변경된 계좌"))
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.is_duel_account").value(false))
                .andExpect(jsonPath("$.initial_capital").value(10_000_000))
                .andExpect(jsonPath("$.cash_balance").value(10_000_000))
                .andExpect(jsonPath("$.available_cash").value(9_300_000))
                .andExpect(jsonPath("$.holdings_market_value").value(0))
                .andExpect(jsonPath("$.total_assets").value(10_000_000))
                .andExpect(jsonPath("$.return_percent").value(0.0))
                .andExpect(jsonPath("$.executed_trade_count").value(0))
                .andExpect(jsonPath("$.ai_delegated").doesNotExist());
    }

    @Test
    void anotherUsersAccountDetailIsNotExposed() throws Exception {
        User owner = users.save(new User("계좌 소유자"));
        User other = users.save(new User("다른 사용자"));
        long accountId = accountId(onboard(owner, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", accountId)
                        .cookie(authCookie(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void missingAccountDetailReturnsNotFound() throws Exception {
        User user = users.save(new User("계좌사용자"));

        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", 999_999L)
                        .cookie(authCookie(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void accountDetailRequiresLogin() throws Exception {
        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", 1L))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startingCapitalOutsideTheAllowedRangeIsRejected() throws Exception {
        User user = users.save(new User("계좌사용자"));

        onboard(user, 999_999)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void onboardingRequiresLogin() throws Exception {
        CsrfCredentials csrf = csrf();

        mvc.perform(post("/api/v1/users/me")
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initial_capital\":10000000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void differentUsersCanEachCreateTheirDefaultAccount() throws Exception {
        User first = users.save(new User("첫 사용자"));
        User second = users.save(new User("두 번째 사용자"));

        onboard(first, 10_000_000).andExpect(status().isCreated());
        onboard(second, 10_000_000).andExpect(status().isCreated());
    }

    @Test
    void holdingsSupportsEmptyAccountsAndRejectsInvalidQueries() throws Exception {
        User user = users.save(new User("보유조회 사용자"));
        var account = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "조회 계좌", 1000000));
        mvc.perform(get("/api/v1/users/me/accounts/{id}/holdings", account.getId()).cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.account_id").value(account.getId()))
                .andExpect(jsonPath("$.holdings").isEmpty())
                .andExpect(jsonPath("$.length()").value(3));
        for (String query : new String[]{"sort=unknown", "order=up", "limit=0", "limit=-1", "limit=abc"}) {
            mvc.perform(get("/api/v1/users/me/accounts/" + account.getId() + "/holdings?" + query)
                            .cookie(authCookie(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_HOLDINGS_QUERY"));
        }
        User other = users.save(new User("다른 사용자"));
        mvc.perform(get("/api/v1/users/me/accounts/{id}/holdings", account.getId()).cookie(authCookie(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/v1/users/me/accounts/{id}/holdings", account.getId()))
                .andExpect(status().isUnauthorized());
    }

    // [계좌 목록 수정 테스트] 본인 활성 계좌는 유형·대결 상태와 무관하게 포함하고, 타 회원·비활성 계좌는 제외한다.
    @Test
    void accountListIncludesAllOwnedActiveAccountsIncludingDuelAi() throws Exception {
        var user = users.save(new User("전체 계좌 조회"));
        var other = users.save(new User("다른 회원"));
        var manual = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "일반", 1000000, false));
        var ai = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "일반 AI", 1000000, true));
        var duelUser = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "대결 사용자", 1000000, false));
        var duelAi = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "대결 AI", 1000000, true));
        var inactive = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "비활성", 1000000, false));
        var inactiveAi = accounts.save(new com.stock_spoon.river_be.account.entity.Account(user, "비활성 대결 AI", 1000000, true));
        var otherAi = accounts.save(new com.stock_spoon.river_be.account.entity.Account(other, "타 회원 대결 AI", 1000000, true));
        var competition = new com.stock_spoon.river_be.competition.entity.Competition(user, "내 대결",
                com.stock_spoon.river_be.competition.entity.Competition.Type.USER_VS_AI,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(1), 1000000, 1);
        var otherCompetition = new com.stock_spoon.river_be.competition.entity.Competition(other, "타 회원 대결",
                com.stock_spoon.river_be.competition.entity.Competition.Type.USER_VS_AI,
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(1), 1000000, 1);
        em.persist(competition);
        em.persist(otherCompetition);
        for (var account : java.util.List.of(duelUser, duelAi, inactiveAi))
            em.persist(new com.stock_spoon.river_be.competition.entity.CompetitionParticipant(competition, account));
        em.persist(new com.stock_spoon.river_be.competition.entity.CompetitionParticipant(otherCompetition, otherAi));
        em.flush();
        em.createNativeQuery("update accounts set is_active=false where account_id in (:first, :second)")
                .setParameter("first", inactive.getId()).setParameter("second", inactiveAi.getId()).executeUpdate();
        // 생성 시각이 같을 때도 ID 순서가 유지되는지 확인한다.
        em.createNativeQuery("update accounts set created_at=:created where user_id=:userId")
                .setParameter("created", java.time.Instant.parse("2026-10-01T00:00:00Z"))
                .setParameter("userId", user.getId()).executeUpdate();
        for (String state : new String[]{"READY", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
            em.createNativeQuery("update competitions set state=:state where competition_id=:id")
                    .setParameter("state", state).setParameter("id", competition.getId()).executeUpdate();
            em.clear();
            mvc.perform(get("/api/v1/users/me/accounts").cookie(authCookie(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$[0].account_id").value(manual.getId()))
                    .andExpect(jsonPath("$[1].account_id").value(ai.getId()))
                    .andExpect(jsonPath("$[2].account_id").value(duelUser.getId()))
                    .andExpect(jsonPath("$[3].account_id").value(duelAi.getId()))
                    .andExpect(jsonPath("$[3].is_duel_account").value(true))
                    .andExpect(jsonPath("$[3].total_assets").value(1000000));
        }
        em.createNativeQuery("update accounts set is_active=false where user_id=:userId")
                .setParameter("userId", user.getId()).executeUpdate();
        em.clear();
        mvc.perform(get("/api/v1/users/me/accounts").cookie(authCookie(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions onboard(User user, long initialCapital)
            throws Exception {
        CsrfCredentials csrf = csrf();
        return mvc.perform(post("/api/v1/users/me")
                .cookie(authCookie(user), csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initial_capital\":" + initialCapital + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions createAccount(User user, String body)
            throws Exception {
        CsrfCredentials csrf = csrf();
        return mvc.perform(post("/api/v1/users/me/accounts")
                .cookie(authCookie(user), csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions renameAccount(
            User user, long accountId, String accountName) throws Exception {
        CsrfCredentials csrf = csrf();
        return mvc.perform(patch("/api/v1/users/me/accounts/{accountId}", accountId)
                .cookie(authCookie(user), csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account_name\":\"" + accountName + "\"}"));
    }

    private long accountId(String responseBody) {
        return ((Number) JsonPath.read(responseBody, "$.account_id")).longValue();
    }

    private Cookie authCookie(User user) {
        return new Cookie("access_token", tokens.issue(user.getId()).accessToken());
    }

    private CsrfCredentials csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new CsrfCredentials(cookie, JsonPath.read(response.getContentAsString(), "$.token"));
    }

    private record CsrfCredentials(Cookie cookie, String token) {
    }
}
