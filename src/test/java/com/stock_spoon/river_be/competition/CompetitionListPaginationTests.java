package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.account.service.AccountService;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class CompetitionListPaginationTests {
    private static final String PATH = "/api/v2/users/me/competitions";
    @Autowired CompetitionRepository competitions;
    @MockitoSpyBean CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired WebApplicationContext context;
    @MockitoBean AccountService accountService;
    @MockitoSpyBean CompetitionTradingHours hours;
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private User owner;
    private Cookie auth;

    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        owner = users.save(new User("목록" + UUID.randomUUID().toString().substring(0, 5)));
        auth = new Cookie("access_token", tokens.issue(owner.getId()).accessToken());
        doReturn(ZonedDateTime.parse("2026-10-21T10:00:00+09:00")).when(hours).now();
        when(accountService.returnPercents(anyList())).thenReturn(Map.of());
    }

    @Test void followsCursorToLastPageWithoutEvaluatingLookaheadAccount() throws Exception {
        var oldest = create(owner, true);
        var middle = create(owner, true);
        var newest = create(owner, true);
        create(users.save(new User("타인" + UUID.randomUUID().toString().substring(0, 5))), true);
        clearInvocations(participants, accountService);
        var first = body(mvc.perform(get(PATH).cookie(auth).param("limit", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.at("/ongoing_competition_count").asLong()).isEqualTo(3);
        assertThat(ids(first)).containsExactly(newest.getId(), middle.getId());
        assertThat(first.at("/pagination/has_next").asBoolean()).isTrue();
        verify(participants).findUserParticipantsInCompetitions(owner.getId(), List.of(newest.getId(), middle.getId()));
        verify(accountService).returnPercents(argThat(list -> list.size() == 2
                && list.stream().noneMatch(a -> a.getName().equals("사용자" + oldest.getId()))));

        String cursor = first.at("/pagination/next_cursor").asText();
        var last = body(mvc.perform(get(PATH).cookie(auth).param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(last)).containsExactly(oldest.getId());
        assertThat(last.at("/pagination/has_next").asBoolean()).isFalse();
        assertThat(last.at("/pagination/next_cursor").isNull()).isTrue();
        assertThat(last.at("/ongoing_competition_count").asLong()).isEqualTo(3);
    }

    @Test void filtersStateAndKeepsGlobalCountEvenOnEmptyPage() throws Exception {
        create(owner, true);
        var ready = create(owner, false);
        var filtered = body(mvc.perform(get(PATH).cookie(auth).param("state", "ready"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ids(filtered)).containsExactly(ready.getId());
        assertThat(filtered.at("/ongoing_competition_count").asLong()).isEqualTo(1);
        clearInvocations(participants, accountService);
        mvc.perform(get(PATH).cookie(auth).param("state", "completed").param("limit", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.competitions").isEmpty())
                .andExpect(jsonPath("$.ongoing_competition_count").value(1))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        verifyNoInteractions(accountService);
        verify(participants, never()).findUserParticipantsInCompetitions(anyLong(), anyCollection());
    }

    @Test void returnsAllWhenPaginationIsOmittedAndPagesOnlyWhenRequested() throws Exception {
        for (int i = 0; i < 11; i++) create(owner, false);
        mvc.perform(get(PATH).cookie(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.competitions.length()").value(11))
                .andExpect(jsonPath("$.pagination").doesNotExist());
        mvc.perform(get(PATH).cookie(auth).param("state", "ready")).andExpect(status().isOk())
                .andExpect(jsonPath("$.competitions.length()").value(11))
                .andExpect(jsonPath("$.pagination").doesNotExist());
        var first = body(mvc.perform(get(PATH).cookie(auth).param("limit", "10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.competitions.length()").value(10))
                .andExpect(jsonPath("$.pagination.limit").value(10))
                .andExpect(jsonPath("$.pagination.has_next").value(true))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get(PATH).cookie(auth).param("cursor", first.at("/pagination/next_cursor").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.competitions.length()").value(1))
                .andExpect(jsonPath("$.pagination.limit").value(10))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
        mvc.perform(get(PATH).cookie(auth).param("limit", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.competitions.length()").value(11))
                .andExpect(jsonPath("$.pagination.has_next").value(false));
    }

    @Test void emptyUnpagedResponseHasNoPagination() throws Exception {
        mvc.perform(get(PATH).cookie(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.competitions").isEmpty())
                .andExpect(jsonPath("$.pagination").doesNotExist());
    }

    @Test void rejectsInvalidQueriesAndCursorFilterMismatch() throws Exception {
        for (String limit : List.of("0", "-1", "101", "abc", "2147483648")) {
            mvc.perform(get(PATH).cookie(auth).param("limit", limit)).andExpect(status().isBadRequest());
        }
        for (String state : List.of("unknown", "", "IN_PROGRESS")) {
            mvc.perform(get(PATH).cookie(auth).param("state", state)).andExpect(status().isBadRequest());
        }
        for (String cursor : List.of("", "invalid!")) {
            mvc.perform(get(PATH).cookie(auth).param("cursor", cursor)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_COMPETITION_CURSOR"));
        }
        create(owner, false);
        create(owner, false);
        var first = body(mvc.perform(get(PATH).cookie(auth).param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get(PATH).cookie(auth).param("cursor", first.at("/pagination/next_cursor").asText())
                        .param("state", "ready"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMPETITION_CURSOR"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    private Competition create(User user, boolean ongoing) {
        var c = new Competition(user, "페이지 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 10_000_000, 1);
        if (ongoing) c.start(ZonedDateTime.parse("2026-10-21T09:00:00+09:00").toInstant());
        c = competitions.save(c);
        participants.save(new CompetitionParticipant(c,
                accounts.save(new Account(user, "사용자" + c.getId(), 10_000_000, false))));
        participants.save(new CompetitionParticipant(c,
                accounts.save(new Account(user, "AI" + c.getId(), 10_000_000, true))));
        return c;
    }
    private JsonNode body(String value) { return json.readTree(value); }
    private List<Long> ids(JsonNode response) {
        return response.get("competitions").valueStream().map(n -> n.get("competition_id").asLong()).toList();
    }
}
