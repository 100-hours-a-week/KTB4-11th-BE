package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompetitionReportTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired CompetitionRepository competitions;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();
    private User owner;
    private Competition competition;

    @BeforeEach
    void setup() {
        owner = users.save(new User("리포트테스트"));
        competition = competitions.save(new Competition(owner, "반도체 집중", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16), 10_000_000, 1));
    }

    @ParameterizedTest
    @EnumSource(Competition.State.class)
    void ownerCanReadReportInEveryCompetitionStateAndMissingDataIsEmpty(Competition.State state) throws Exception {
        ReflectionTestUtils.setField(competition, "state", state);
        competitions.save(competition);
        var body = get(competition.getId(), owner, 200);
        assertThat((String) JsonPath.read(body, "$.competition_name")).isEqualTo("반도체 집중");
        assertThat((String) JsonPath.read(body, "$.start_date")).isEqualTo("2026-10-12");
        assertThat((String) JsonPath.read(body, "$.end_date")).isEqualTo("2026-10-16");
        assertThat((String) JsonPath.read(body, "$.state")).isEqualTo(state.name().toLowerCase(java.util.Locale.ROOT));
        assertThat((List<?>) JsonPath.read(body, "$.market_analyses")).isEmpty();
        assertThat((List<?>) JsonPath.read(body, "$.judgment_summaries")).isEmpty();
    }

    @Test
    void returnsStoredFullTextNewestFirstAndOnlyForSelectedCompetition() throws Exception {
        var other = competitions.save(new Competition(owner, "다른 대결", Competition.Type.USER_VS_AI,
                competition.getStartDate(), competition.getEndDate(), 10_000_000, 1));
        analysis(other.getId(), "2026-10-16", "다른 대결", "노출되면 안 됨");
        analysis(competition.getId(), "2026-10-13", "이전 분석", "이전 본문");
        analysis(competition.getId(), "2026-10-15", "최신 분석", "펼쳐 볼 본문\n전체 내용을 그대로 반환");
        jdbc.update("insert into competition_judgment_summaries (competition_id, title, content) values (?, ?, ?)",
                competition.getId(), "인플레이션", "물가와 금리 전망");
        jdbc.update("insert into competition_judgment_summaries (competition_id, title, content) values (?, ?, ?)",
                other.getId(), "다른 대결 요약", "노출되면 안 됨");
        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.market_analyses")).hasSize(2);
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].date")).isEqualTo("2026-10-15");
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].content")).isEqualTo("펼쳐 볼 본문\n전체 내용을 그대로 반환");
        assertThat((Number) JsonPath.read(body, "$.market_analyses[0].analysis_id")).isNotNull();
        assertThat((List<?>) JsonPath.read(body, "$.judgment_summaries")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.judgment_summaries[0].title")).isEqualTo("인플레이션");
    }

    @Test
    void rejectsUnauthenticatedOtherOwnerAndUnknownCompetition() throws Exception {
        get(competition.getId(), null, 401);
        var other = users.save(new User("리포트타인"));
        var denied = get(competition.getId(), other, 404);
        assertThat((String) JsonPath.read(denied, "$.code")).isEqualTo("COMPETITION_NOT_FOUND");
        get(Long.MAX_VALUE, owner, 404);
    }

    private void analysis(long competitionId, String date, String title, String content) {
        jdbc.update("insert into competition_market_analyses (competition_id, analysis_date, title, content) values (?, ?, ?, ?)",
                competitionId, LocalDate.parse(date), title, content);
    }
    private String get(long id, User user, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/users/me/competitions/" + id + "/report"))
                .timeout(Duration.ofSeconds(20));
        if (user != null) request.header("Cookie", "access_token=" + tokens.issue(user.getId()).accessToken());
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return response.body();
    }
}
