package com.stock_spoon.river_be.account.controller;

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
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired JwtTokenProvider tokens;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void onboardingCreatesTheDefaultAccountAndCompletesOnboarding() throws Exception {
        User user = users.save(new User("계좌사용자"));

        onboard(user, 10_000_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account_name").value("기본 계좌"))
                .andExpect(jsonPath("$.initial_capital").value(10_000_000))
                .andExpect(jsonPath("$.cash_balance").value(10_000_000))
                .andExpect(jsonPath("$.ai_delegated").value(true));

        assertThat(users.findById(user.getId()).orElseThrow().isOnboardingCompleted()).isTrue();
        assertThat(accounts.findAll()).singleElement().satisfies(account -> {
            assertThat(account.isActive()).isTrue();
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
                .andExpect(jsonPath("$[1].account_id").value(secondAccountId))
                .andExpect(jsonPath("$[1].account_name").value("두 번째 계좌"))
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
    void accountDetailReturnsTheUsersActiveAccount() throws Exception {
        User user = users.save(new User("계좌사용자"));
        long accountId = accountId(onboard(user, 10_000_000)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        renameAccount(user, accountId, "변경된 계좌")
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/me/accounts/{accountId}", accountId)
                        .cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account_id").value(accountId))
                .andExpect(jsonPath("$.account_name").value("변경된 계좌"))
                .andExpect(jsonPath("$.initial_capital").value(10_000_000))
                .andExpect(jsonPath("$.cash_balance").value(10_000_000))
                .andExpect(jsonPath("$.ai_delegated").value(true));
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

        mvc.perform(post("/api/v1/users/me/onboarding")
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

    private org.springframework.test.web.servlet.ResultActions onboard(User user, long initialCapital)
            throws Exception {
        CsrfCredentials csrf = csrf();
        return mvc.perform(post("/api/v1/users/me/onboarding")
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
