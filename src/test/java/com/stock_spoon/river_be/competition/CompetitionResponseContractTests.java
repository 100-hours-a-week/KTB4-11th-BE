package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.market.RedisMarketPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompetitionResponseContractTests {
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @Autowired CompetitionDailyPerformanceRepository history;
    @Autowired HoldingRepository holdings;
    @Autowired JwtTokenProvider tokens;
    @Autowired WebApplicationContext context;
    @Autowired CompetitionService service;
    @MockitoBean KiwoomMarketClient market;
    @MockitoBean RedisMarketPrice prices;
    @MockitoSpyBean CompetitionTradingHours hours;
    private final LocalDate day = LocalDate.of(2026, 11, 6);
    private record Fixture(User user, Competition competition, List<CompetitionParticipant> links) {}
    private MockMvc mvc() { return webAppContextSetup(context).apply(springSecurity()).build(); }
    private Cookie auth(User user) { return new Cookie("access_token", tokens.issue(user.getId()).accessToken()); }
    private Fixture fixture(boolean aiOnly, boolean completed, boolean tied) {
        var user = users.save(new User("응답" + UUID.randomUUID().toString().substring(0,6)));
        var c = new Competition(user, "응답 통일", aiOnly ? Competition.Type.AI_VS_AI : Competition.Type.USER_VS_AI,
                day.minusDays(4), day, 3_000_000, aiOnly ? 2 : 1);
        ReflectionTestUtils.setField(c, "state", completed ? Competition.State.COMPLETED : Competition.State.IN_PROGRESS);
        ReflectionTestUtils.setField(c, "startedAt", day.minusDays(4).atTime(9,0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        if (completed) ReflectionTestUtils.setField(c, "winner", tied ? Competition.Winner.DRAW : aiOnly ? Competition.Winner.AI : Competition.Winner.USER);
        c = competitions.save(c);
        var links = new ArrayList<CompetitionParticipant>();
        for (int i=0;i<2;i++) {
            var a = accounts.save(new Account(user,"계좌"+i,3_000_000,aiOnly || i==1));
            var p = new CompetitionParticipant(c,a);
            if(completed) p.recordFinalAssets(i==0 || tied ? 3_166_500 : 3_166_499);
            p=participants.save(p);links.add(p);
            history.save(new CompetitionDailyPerformance(p,day,completed ? p.getFinalAssetValue() : i==0 ? 3_166_500 : 2_833_500));
            if(aiOnly) agents.save(new CompetitionAgent(p,i, i==0?5:1,List.of("반도체"),List.of(),""));
        }
        doReturn(day.atTime(16,0).atZone(ZoneId.of("Asia/Seoul"))).when(hours).now();
        return new Fixture(user,c,links);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void detailRemovesTopLevelDuplicatesAndRoundsAllParticipantRates(boolean aiOnly) throws Exception {
        var f=fixture(aiOnly,false,false);
        var a=f.links().getFirst().getAccount();a.changeCash(-1_000_000);accounts.save(a);
        holdings.save(new Holding(a,"000660",10,new BigDecimal("1000000")));
        when(prices.currentPrice("000660")).thenReturn(116_650L);
        when(market.stockDetails("000660")).thenReturn(new KiwoomMarketClient.StockDetails("000660","SK하이닉스",null));
        var response = mvc().perform(get("/api/v2/users/me/competitions/"+f.competition().getId()).cookie(auth(f.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cumulative_return_percent").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.daily_performances").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.d_day").value(0))
                .andExpect(jsonPath("$.participants[0].cumulative_return_percent").value(5.6))
                .andExpect(jsonPath("$.participants[0].top_return_holding.return_percent").value(16.7))
                .andExpect(jsonPath("$.participants[0].daily_performances[0].cumulative_return_percent").value(5.6))
                .andExpect(jsonPath("$.participants[1].daily_performances[0].cumulative_return_percent").value(-5.6)).andReturn();
        saveExample(aiOnly, "detail", response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test
    void topHoldingRoundsOnlyOnceAtTheHalfwayBoundary() throws Exception {
        var f=fixture(false,false,false);
        var a=f.links().getFirst().getAccount();a.changeCash(-3_000_000);accounts.save(a);
        holdings.save(new Holding(a,"000660",1,new BigDecimal("3000000")));
        when(prices.currentPrice("000660")).thenReturn(3_499_499L);
        when(market.stockDetails("000660")).thenReturn(new KiwoomMarketClient.StockDetails("000660","SK하이닉스",null));
        mvc().perform(get("/api/v2/users/me/competitions/"+f.competition().getId()).cookie(auth(f.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].cumulative_return_percent").value(16.6))
                .andExpect(jsonPath("$.participants[0].top_return_holding.return_percent").value(16.6));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void resultUsesCommonParticipantsAndRoundsOnceFromFrozenAssets(boolean aiOnly) throws Exception {
        var f=fixture(aiOnly,true,false);
        var response = mvc().perform(get("/api/v2/users/me/competitions/"+f.competition().getId()+"/result").cookie(auth(f.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.d_day").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.winner").value(aiOnly?"AI 1":"나"))
                .andExpect(jsonPath("$.winner_participant_ids[0]").value(f.links().getFirst().getId().intValue()))
                .andExpect(jsonPath("$.user_final_return_percent").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ai_final_return_percent").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.daily_performances").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.participants[0].final_return_percent").value(5.6))
                .andExpect(jsonPath("$.participants[1].final_return_percent").value(5.5))
                .andExpect(jsonPath("$.participants[0].rank").value(1))
                .andExpect(jsonPath("$.participants[1].rank").value(2))
                .andExpect(jsonPath("$.participants[0].executed_trade_count").value(0))
                .andExpect(jsonPath("$.participants[0].strategy").value(aiOnly?org.hamcrest.Matchers.notNullValue():org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.participants[0].daily_performances[0].cumulative_return_percent").value(5.6)).andReturn();
        saveExample(aiOnly, "result", response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        verifyNoInteractions(market,prices);
        assertThat(participants.findById(f.links().getFirst().getId()).orElseThrow().getFinalReturnPercent()).isEqualByComparingTo("5.5500");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void tiedWinnersUseJoinedDisplayNamesAndAllIds(boolean aiOnly) throws Exception {
        var f=fixture(aiOnly,true,true);
        var response = mvc().perform(get("/api/v2/users/me/competitions/"+f.competition().getId()+"/result").cookie(auth(f.user())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.winner").value(aiOnly?"AI 1, AI 2":"나, AI"))
                .andExpect(jsonPath("$.winner_participant_ids.length()").value(2))
                .andExpect(jsonPath("$.participants[0].rank").value(1)).andExpect(jsonPath("$.participants[1].rank").value(1)).andReturn();
    }
    private void saveExample(boolean aiOnly, String endpoint, String body) throws java.io.IOException {
        var path = java.nio.file.Path.of("build/reports/competition-response-" + (aiOnly ? "ai" : "user") + "-" + endpoint + ".json");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, body);
    }
}
