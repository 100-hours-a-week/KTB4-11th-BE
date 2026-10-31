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
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    @Autowired com.stock_spoon.river_be.config.JwtProperties jwtProperties;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionReportService reportService;
    private final tools.jackson.databind.json.JsonMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().build();
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
    void returnsNewsWithUnknownDateAsExplicitNull() throws Exception {
        jdbc.update("insert into competition_market_analyses (competition_id, analysis_date, title, content) values (?, ?, ?, ?)",
                competition.getId(), null, "카카오 데이터센터 화재", "서비스 장애");
        var body = get(competition.getId(), owner, 200);
        java.util.Map<String, Object> item = JsonPath.read(body, "$.market_analyses[0]");
        assertThat(item).containsEntry("date", null).containsEntry("title", "카카오 데이터센터 화재")
                .containsEntry("content", "서비스 장애");
    }

    @Test
    void savingReportReplacesExistingContentAndAllowsEmptyArrays() throws Exception {
        var first = objectMapper.readValue("""
                {"news":[{"title":"첫 뉴스","summary":"첫 요약"}],
                 "thoughts":[{"title":"첫 판단","text":"첫 내용"}]}
                """, com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
        var second = objectMapper.readValue("""
                {"news":[{"title":"새 뉴스","summary":"새 요약"}],"thoughts":[]}
                """, com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);

        reportService.save(owner.getId(), competition.getId(), first);
        reportService.save(owner.getId(), competition.getId(), second);

        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.market_analyses")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].title")).isEqualTo("새 뉴스");
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].content")).isEqualTo("새 요약");
        assertThat((List<?>) JsonPath.read(body, "$.judgment_summaries")).isEmpty();
    }

    @Test
    void savingReportRejectsOtherOwnersCompetition() throws Exception {
        var other = users.save(new User("다른 사용자"));
        var request = objectMapper.readValue("{\"news\":[],\"thoughts\":[]}",
                com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reportService.save(other.getId(), competition.getId(), request))
                .isInstanceOf(com.stock_spoon.river_be.competition.exception.CompetitionException.class);
    }

    @Test
    void failedReplacementKeepsTheEntirePreviousReport() throws Exception {
        var original = objectMapper.readValue("""
                {"news":[{"title":"기존 뉴스","summary":"기존 요약"}],
                 "thoughts":[{"title":"기존 판단","text":"기존 내용"}]}
                """, com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
        reportService.save(owner.getId(), competition.getId(), original);

        // 서비스에서 직접 호출해 요청 검증을 건너뛰고 DB 저장 실패를 재현한다.
        String oversizedTitle = "가".repeat(256);
        var invalid = objectMapper.readValue("""
                {"news":[{"title":"새 뉴스","summary":"새 요약"}],
                 "thoughts":[{"title":"%s","text":"새 내용"}]}
                """.formatted(oversizedTitle), com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reportService.save(owner.getId(), competition.getId(), invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.market_analyses")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].title")).isEqualTo("기존 뉴스");
        assertThat((String) JsonPath.read(body, "$.market_analyses[0].content")).isEqualTo("기존 요약");
        assertThat((List<?>) JsonPath.read(body, "$.judgment_summaries")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.judgment_summaries[0].title")).isEqualTo("기존 판단");
        assertThat((String) JsonPath.read(body, "$.judgment_summaries[0].content")).isEqualTo("기존 내용");
    }

    @Test
    void concurrentReportSavesDoNotMixNewsAndThoughts() throws Exception {
        var first = objectMapper.readValue("""
                {"news":[{"title":"첫 뉴스","summary":"첫 요약"}],
                 "thoughts":[{"title":"첫 판단","text":"첫 내용"}]}
                """, com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
        var second = objectMapper.readValue("""
                {"news":[{"title":"두 번째 뉴스","summary":"두 번째 요약"}],
                 "thoughts":[{"title":"두 번째 판단","text":"두 번째 내용"}]}
                """, com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);

        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var firstSave = executor.submit(() -> {
                ready.countDown();
                start.await();
                reportService.save(owner.getId(), competition.getId(), first);
                return null;
            });
            var secondSave = executor.submit(() -> {
                ready.countDown();
                start.await();
                reportService.save(owner.getId(), competition.getId(), second);
                return null;
            });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstSave.get(20, java.util.concurrent.TimeUnit.SECONDS);
            secondSave.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.market_analyses")).hasSize(1);
        assertThat((List<?>) JsonPath.read(body, "$.judgment_summaries")).hasSize(1);
        String newsTitle = JsonPath.read(body, "$.market_analyses[0].title");
        String thoughtTitle = JsonPath.read(body, "$.judgment_summaries[0].title");
        assertThat(java.util.List.of(newsTitle, thoughtTitle)).isIn(
                java.util.List.of("첫 뉴스", "첫 판단"),
                java.util.List.of("두 번째 뉴스", "두 번째 판단"));
    }

    @Test
    void aiServerCanPostReportAndOwnerCanReadItOverHttp() throws Exception {
        String base = "http://localhost:" + port;
        var csrfResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        String csrfToken = JsonPath.read(csrfResponse.body(), "$.token");
        String csrfHeader = JsonPath.read(csrfResponse.body(), "$.header_name");
        String csrfCookie = csrfResponse.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";", 2)[0];

        Instant now = Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer()).subject(owner.getId().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("type", "access").claim("actor", "AI").build();
        String aiToken = jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
        String body = """
                {"news":[{"title":"카카오 데이터센터 화재","summary":"서비스 장애"}],
                 "thoughts":[{"title":"인플레이션","text":"물가 상승 영향"}]}
                """;
        var post = HttpRequest.newBuilder(URI.create(base + "/api/v1/competitions/" + competition.getId() + "/report"))
                .header("Cookie", "access_token=" + aiToken + "; " + csrfCookie)
                .header(csrfHeader, csrfToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).as(postResponse.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(postResponse.body(), "$.competition_id")).longValue()).isEqualTo(competition.getId());
        assertThat((String) JsonPath.read(postResponse.body(), "$.message")).isEqualTo("success");

        var response = get(competition.getId(), owner, 200);
        assertThat((String) JsonPath.read(response, "$.market_analyses[0].title")).isEqualTo("카카오 데이터센터 화재");
        assertThat((String) JsonPath.read(response, "$.market_analyses[0].content")).isEqualTo("서비스 장애");
        java.util.Map<String, Object> marketItem = JsonPath.read(response, "$.market_analyses[0]");
        assertThat(marketItem).containsEntry("date", null);
        assertThat((String) JsonPath.read(response, "$.judgment_summaries[0].title")).isEqualTo("인플레이션");
        assertThat((String) JsonPath.read(response, "$.judgment_summaries[0].content")).isEqualTo("물가 상승 영향");
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
