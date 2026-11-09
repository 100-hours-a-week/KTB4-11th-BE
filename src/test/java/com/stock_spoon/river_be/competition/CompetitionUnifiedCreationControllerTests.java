package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.CompetitionTradingHours;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class CompetitionUnifiedCreationControllerTests {
    static final String URL = "/api/v2/users/me/competitions";
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper json;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @MockitoSpyBean CompetitionTradingHours hours;
    final ZonedDateTime now = ZonedDateTime.parse("2026-11-03T10:00:00+09:00[Asia/Seoul]");
    MockMvc mvc;
    User owner;
    Cookie auth, csrf;
    String csrfToken;

    @BeforeEach void setup() throws Exception {
        doReturn(now).when(hours).now();
        owner = new User("HTTP 생성"); owner.completeOnboarding(); owner = users.save(owner);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        auth = new Cookie("access_token", tokens.issue(owner.getId()).accessToken());
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        csrf = response.getCookie("XSRF-TOKEN");
        csrfToken = json.readTree(response.getContentAsString()).get("token").asText();
    }

    @Test void userVsAiAcceptsOmittedAndNullAgentsWithOriginalResponse() throws Exception {
        for (boolean explicitNull : new boolean[]{false, true}) {
            var payload = body("user_vs_ai", 1);
            payload.remove("ai_participant_count"); // 기존 기본값 1을 유지한다.
            if (explicitNull) payload.put("agents", null);
            created(payload).andExpect(jsonPath("$.state").value("in_progress"))
                    .andExpect(jsonPath("$.agents").doesNotExist());
        }
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void aiVsAiPersistsStrategiesAndUsesJwtOwner(int count) throws Exception {
        var payload = body("ai_vs_ai", count);
        payload.put("agents", strategies(count));
        var other = users.save(new User("본문 사용자"));
        payload.put("user_id", other.getId());
        var response = created(payload).andExpect(jsonPath("$.state").value("ready"))
                .andReturn().getResponse();
        long id = json.readTree(response.getContentAsString()).get("competition_id").asLong();
        assertThat(competitions.findByIdAndUserId(id, owner.getId())).isPresent();
        assertThat(competitions.findByIdAndUserId(id, other.getId())).isEmpty();
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(id)).hasSize(count);
        assertThat(agents.findAllByParticipantCompetitionIdOrderByPositionAsc(id))
                .extracting(a -> a.getRiskLevel()).containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, count).map(i -> i == 0 ? 1 : 5).boxed().toList());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "null", "empty", "nullItem", "riskMissing", "riskZero",
            "riskSix", "themeMissing", "blankTheme", "longTheme", "longRequest", "scalar", "countZero", "countFive"})
    void invalidInputsReturn400WithoutCreatingAnything(String scenario) throws Exception {
        var payload = body("ai_vs_ai", 2);
        var strategies = strategies(2);
        payload.put("agents", strategies);
        switch (scenario) {
            case "missing" -> payload.remove("agents");
            case "null" -> payload.put("agents", null);
            case "empty" -> payload.put("agents", List.of());
            case "nullItem" -> strategies.set(1, null);
            case "riskMissing" -> strategies.get(1).remove("risk_level");
            case "riskZero" -> strategies.get(1).put("risk_level", 0);
            case "riskSix" -> strategies.get(1).put("risk_level", 6);
            case "themeMissing" -> strategies.get(1).remove("preferred_themes");
            case "blankTheme" -> strategies.get(1).put("preferred_themes", List.of(" "));
            case "longTheme" -> strategies.get(1).put("preferred_themes", List.of("가".repeat(256)));
            case "longRequest" -> strategies.get(1).put("additional_request", "가".repeat(4001));
            case "scalar" -> payload.put("agents", "잘못된 타입");
            case "countZero" -> payload.put("ai_participant_count", 0);
            case "countFive" -> payload.put("ai_participant_count", 5);
            default -> throw new IllegalArgumentException(scenario);
        }
        long before = competitions.count();
        perform(payload).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(competitions.count()).isEqualTo(before);
    }

    @Test void rejectsCountMismatchConflictingThemesAndUserVsAiStrategies() throws Exception {
        var payload = body("ai_vs_ai", 3);
        payload.put("agents", strategies(2));
        perform(payload).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AGENT_COUNT_MISMATCH"));
        payload = body("ai_vs_ai", 2);
        var strategies = strategies(2);
        strategies.get(1).put("excluded_themes", List.of(" 반도체 "));
        payload.put("agents", strategies);
        perform(payload).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AGENT_THEMES"));
        payload = body("user_vs_ai", 1); payload.put("agents", strategies(2));
        perform(payload).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void requiresAuthenticationAndCsrfAndRejectsMalformedJson() throws Exception {
        var payload = body("ai_vs_ai", 2); payload.put("agents", strategies(2));
        String content = json.writeValueAsString(payload);
        mvc.perform(post(URL).cookie(csrf).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).cookie(auth).contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).cookie(auth, csrf).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    ResultActions created(Map<String, Object> payload) throws Exception {
        return perform(payload).andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.competition_id").isNumber());
    }
    ResultActions perform(Map<String, Object> payload) throws Exception {
        return mvc.perform(post(URL).cookie(auth, csrf).header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)));
    }
    Map<String, Object> body(String type, int count) {
        var body = new LinkedHashMap<String, Object>();
        body.put("competition_name", "HTTP-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("competition_type", type); body.put("start_date", now.toLocalDate().toString());
        body.put("end_date", now.toLocalDate().plusDays(30).toString());
        body.put("initial_capital", 3_000_000); body.put("ai_participant_count", count);
        return body;
    }
    List<Map<String, Object>> strategies(int count) {
        var agents = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) {
            var strategy = new LinkedHashMap<String, Object>();
            strategy.put("risk_level", i == 0 ? 1 : 5); strategy.put("preferred_themes", List.of("반도체"));
            strategy.put("excluded_themes", List.of()); strategy.put("additional_request", ""); agents.add(strategy);
        }
        return agents;
    }
}
