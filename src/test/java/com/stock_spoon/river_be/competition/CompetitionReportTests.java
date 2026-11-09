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
    @Autowired com.stock_spoon.river_be.account.repository.AccountRepository accounts;
    @Autowired com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository participants;
    @Autowired com.stock_spoon.river_be.competition.repository.CompetitionAgentRepository agents;
    @Autowired com.stock_spoon.river_be.competition.service.CompetitionReportService reportService;
    private final tools.jackson.databind.json.JsonMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();
    private User owner;
    private Competition competition;
    private com.stock_spoon.river_be.competition.entity.CompetitionParticipant ai;

    @BeforeEach
    void setup() {
        owner = users.save(new User("리포트테스트"));
        competition = competitions.save(new Competition(owner, "반도체 집중", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16), 10_000_000, 1));
        var account = accounts.save(new com.stock_spoon.river_be.account.entity.Account(owner, "AI", 10_000_000, true));
        ai = participants.save(new com.stock_spoon.river_be.competition.entity.CompetitionParticipant(competition, account));
        var human = accounts.save(new com.stock_spoon.river_be.account.entity.Account(owner, "사용자", 10_000_000, false));
        participants.save(new com.stock_spoon.river_be.competition.entity.CompetitionParticipant(competition, human));
    }

    @Test void wrapsSingleAiReportInReportsArrayWithParticipantIdentity() throws Exception {
        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.reports")).hasSize(1);
        assertThat(((Number) JsonPath.read(body, "$.reports[0].participant_id")).longValue()).isEqualTo(ai.getId());
        assertThat((String) JsonPath.read(body, "$.reports[0].display_name")).isEqualTo("AI");
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("pending");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).isEmpty();
        java.util.Map<String, Object> root = JsonPath.read(body, "$");
        assertThat(root).doesNotContainKeys("report_state", "market_analyses", "judgment_summaries");
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
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("pending");
        assertThat(((java.util.Map<?, ?>) JsonPath.read(body, "$")).containsKey("state")).isFalse();
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).isEmpty();
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).isEmpty();
    }

    @Test void savedEmptyReportIsCompletedRatherThanPending() throws Exception {
        var request = request("{\"news\":[],\"thoughts\":[]}");
        reportService.save(owner.getId(), competition.getId(), request);
        var body = get(competition.getId(), owner, 200);
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("completed");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).isEmpty();
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).isEmpty();
    }

    @Test void failedInitialSaveLeavesReportPending() throws Exception {
        var invalid = request("""
                {"news":[{"title":"뉴스","summary":"내용"}],
                 "thoughts":[{"title":"%s","text":"내용"}]}
                """.formatted("가".repeat(256)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reportService.save(owner.getId(), competition.getId(), invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var body = get(competition.getId(), owner, 200);
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("pending");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).isEmpty();
    }

    @Test
    void returnsNewsWithUnknownDateAsExplicitNull() throws Exception {
        jdbc.update("insert into competition_market_analyses (competition_id, participant_id, analysis_date, title, content) values (?, ?, ?, ?, ?)",
                competition.getId(), ai.getId(), null, "카카오 데이터센터 화재", "서비스 장애");
        jdbc.update("update competition_participants set report_ready=true where competition_id=?", competition.getId());
        var body = get(competition.getId(), owner, 200);
        java.util.Map<String, Object> item = JsonPath.read(body, "$.reports[0].market_analyses[0]");
        assertThat(item).containsEntry("date", null).containsEntry("title", "카카오 데이터센터 화재")
                .containsEntry("content", "서비스 장애");
    }

    @Test
    void savingReportReplacesExistingContentAndAllowsEmptyArrays() throws Exception {
        var first = request("""
                {"news":[{"title":"첫 뉴스","summary":"첫 요약"}],
                 "thoughts":[{"title":"첫 판단","text":"첫 내용"}]}
                """);
        var second = request("""
                {"news":[{"title":"새 뉴스","summary":"새 요약"}],"thoughts":[]}
                """);

        reportService.save(owner.getId(), competition.getId(), first);
        reportService.save(owner.getId(), competition.getId(), second);

        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("completed");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].title")).isEqualTo("새 뉴스");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].content")).isEqualTo("새 요약");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).isEmpty();
    }

    @Test
    void savingReportRejectsOtherOwnersCompetition() throws Exception {
        var other = users.save(new User("다른 사용자"));
        var request = request("{\"news\":[],\"thoughts\":[]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reportService.save(other.getId(), competition.getId(), request))
                .isInstanceOf(com.stock_spoon.river_be.competition.exception.CompetitionException.class);
    }

    @Test
    void failedReplacementKeepsTheEntirePreviousReport() throws Exception {
        var original = request("""
                {"news":[{"title":"기존 뉴스","summary":"기존 요약"}],
                 "thoughts":[{"title":"기존 판단","text":"기존 내용"}]}
                """);
        reportService.save(owner.getId(), competition.getId(), original);

        // 서비스에서 직접 호출해 요청 검증을 건너뛰고 DB 저장 실패를 재현한다.
        String oversizedTitle = "가".repeat(256);
        var invalid = request("""
                {"news":[{"title":"새 뉴스","summary":"새 요약"}],
                 "thoughts":[{"title":"%s","text":"새 내용"}]}
                """.formatted(oversizedTitle));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                reportService.save(owner.getId(), competition.getId(), invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].title")).isEqualTo("기존 뉴스");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].content")).isEqualTo("기존 요약");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.reports[0].judgment_summaries[0].title")).isEqualTo("기존 판단");
        assertThat((String) JsonPath.read(body, "$.reports[0].judgment_summaries[0].content")).isEqualTo("기존 내용");
    }

    @Test
    void concurrentReportSavesDoNotMixNewsAndThoughts() throws Exception {
        var first = request("""
                {"news":[{"title":"첫 뉴스","summary":"첫 요약"}],
                 "thoughts":[{"title":"첫 판단","text":"첫 내용"}]}
                """);
        var second = request("""
                {"news":[{"title":"두 번째 뉴스","summary":"두 번째 요약"}],
                 "thoughts":[{"title":"두 번째 판단","text":"두 번째 내용"}]}
                """);

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
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).hasSize(1);
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).hasSize(1);
        String newsTitle = JsonPath.read(body, "$.reports[0].market_analyses[0].title");
        String thoughtTitle = JsonPath.read(body, "$.reports[0].judgment_summaries[0].title");
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
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request(body)))).build();
        var postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertThat(postResponse.statusCode()).as(postResponse.body()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(postResponse.body(), "$.competition_id")).longValue()).isEqualTo(competition.getId());
        assertThat((String) JsonPath.read(postResponse.body(), "$.message")).isEqualTo("success");

        var response = get(competition.getId(), owner, 200);
        assertThat((String) JsonPath.read(response, "$.reports[0].market_analyses[0].title")).isEqualTo("카카오 데이터센터 화재");
        assertThat((String) JsonPath.read(response, "$.reports[0].market_analyses[0].content")).isEqualTo("서비스 장애");
        java.util.Map<String, Object> marketItem = JsonPath.read(response, "$.reports[0].market_analyses[0]");
        assertThat(marketItem).containsEntry("date", null);
        assertThat((String) JsonPath.read(response, "$.reports[0].judgment_summaries[0].title")).isEqualTo("인플레이션");
        assertThat((String) JsonPath.read(response, "$.reports[0].judgment_summaries[0].content")).isEqualTo("물가 상승 영향");
    }

    @Test
    void returnsStoredFullTextNewestFirstAndOnlyForSelectedCompetition() throws Exception {
        var other = competitions.save(new Competition(owner, "다른 대결", Competition.Type.USER_VS_AI,
                competition.getStartDate(), competition.getEndDate(), 10_000_000, 1));
        addAi(other, "다른AI");
        analysis(other.getId(), "2026-10-16", "다른 대결", "노출되면 안 됨");
        analysis(competition.getId(), "2026-10-13", "이전 분석", "이전 본문");
        analysis(competition.getId(), "2026-10-15", "최신 분석", "펼쳐 볼 본문\n전체 내용을 그대로 반환");
        jdbc.update("insert into competition_judgment_summaries (competition_id, participant_id, title, content) values (?, ?, ?, ?)",
                competition.getId(), ai.getId(), "인플레이션", "물가와 금리 전망");
        jdbc.update("insert into competition_judgment_summaries (competition_id, participant_id, title, content) values (?, ?, ?, ?)",
                other.getId(), participantId(other.getId()), "다른 대결 요약", "노출되면 안 됨");
        var body = get(competition.getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).hasSize(2);
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].date")).isEqualTo("2026-10-15");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].content")).isEqualTo("펼쳐 볼 본문\n전체 내용을 그대로 반환");
        assertThat((Number) JsonPath.read(body, "$.reports[0].market_analyses[0].analysis_id")).isNotNull();
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].judgment_summaries")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.reports[0].judgment_summaries[0].title")).isEqualTo("인플레이션");
    }

    @Test
    void rejectsUnauthenticatedOtherOwnerAndUnknownCompetition() throws Exception {
        get(competition.getId(), null, 401);
        var other = users.save(new User("리포트타인"));
        var denied = get(competition.getId(), other, 404);
        assertThat((String) JsonPath.read(denied, "$.code")).isEqualTo("COMPETITION_NOT_FOUND");
        get(Long.MAX_VALUE, owner, 404);
    }

    @Test void multiAiReportIncludesPendingAndUsesAgentPositionRatherThanParticipantId() throws Exception {
        var target = multiAi();
        reportService.save(owner.getId(), target.competition().getId(), request(target.first().getId(), """
                {"news":[{"title":"AI1 뉴스","summary":"AI1 분석"}],"thoughts":[]}
                """));
        var body = get(target.competition().getId(), owner, 200);
        assertThat((List<?>) JsonPath.read(body, "$.reports")).hasSize(2);
        assertThat(((Number) JsonPath.read(body, "$.reports[0].participant_id")).longValue()).isEqualTo(target.first().getId());
        assertThat((String) JsonPath.read(body, "$.reports[0].display_name")).isEqualTo("AI 1");
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("completed");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].title")).isEqualTo("AI1 뉴스");
        assertThat(((Number) JsonPath.read(body, "$.reports[1].participant_id")).longValue()).isEqualTo(target.second().getId());
        assertThat((String) JsonPath.read(body, "$.reports[1].display_name")).isEqualTo("AI 2");
        assertThat((String) JsonPath.read(body, "$.reports[1].report_state")).isEqualTo("pending");
        assertThat((List<?>) JsonPath.read(body, "$.reports[1].market_analyses")).isEmpty();
        assertThat((List<?>) JsonPath.read(body, "$.reports[1].judgment_summaries")).isEmpty();
    }

    @Test void replacingOneAiReportPreservesOtherAiAndAllowsCompletedEmptyReport() throws Exception {
        var target = multiAi();
        reportService.save(owner.getId(), target.competition().getId(), request(target.first().getId(), """
                {"news":[{"title":"AI1 뉴스","summary":"내용"}],"thoughts":[]}
                """));
        reportService.save(owner.getId(), target.competition().getId(), request(target.second().getId(), """
                {"news":[{"title":"AI2 뉴스","summary":"분석"}],"thoughts":[{"title":"AI2 판단","text":"판단 내용"}]}
                """));
        var before = get(target.competition().getId(), owner, 200);
        Number preservedId = JsonPath.read(before, "$.reports[1].market_analyses[0].analysis_id");
        reportService.save(owner.getId(), target.competition().getId(), request(target.first().getId(), "{\"news\":[],\"thoughts\":[]}"));
        var body = get(target.competition().getId(), owner, 200);
        assertThat((String) JsonPath.read(body, "$.reports[0].report_state")).isEqualTo("completed");
        assertThat((List<?>) JsonPath.read(body, "$.reports[0].market_analyses")).isEmpty();
        assertThat((String) JsonPath.read(body, "$.reports[1].report_state")).isEqualTo("completed");
        assertThat((Number) JsonPath.read(body, "$.reports[1].market_analyses[0].analysis_id")).isEqualTo(preservedId);
        assertThat((String) JsonPath.read(body, "$.reports[1].judgment_summaries[0].title")).isEqualTo("AI2 판단");
    }

    @Test void concurrentSavesForDifferentAiPreserveBothReports() throws Exception {
        var target = multiAi();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                reportService.save(owner.getId(), target.competition().getId(), request(target.first().getId(), """
                        {"news":[{"title":"AI1 뉴스","summary":"내용"}],"thoughts":[]}
                        """));
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                reportService.save(owner.getId(), target.competition().getId(), request(target.second().getId(), """
                        {"news":[],"thoughts":[{"title":"AI2 판단","text":"내용"}]}
                        """));
                return null;
            });
            start.countDown();
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        var body = get(target.competition().getId(), owner, 200);
        assertThat((List<String>) JsonPath.<List<String>>read(body, "$.reports[*].report_state")).containsExactly("completed", "completed");
        assertThat((String) JsonPath.read(body, "$.reports[0].market_analyses[0].title")).isEqualTo("AI1 뉴스");
        assertThat((String) JsonPath.read(body, "$.reports[1].judgment_summaries[0].title")).isEqualTo("AI2 판단");
    }

    @Test void failedReplacementOfOneAiPreservesBothAiReports() throws Exception {
        var target = multiAi();
        for (var participant : List.of(target.first(), target.second())) {
            reportService.save(owner.getId(), target.competition().getId(), request(participant.getId(), """
                    {"news":[{"title":"원본","summary":"분석"}],"thoughts":[{"title":"판단","text":"내용"}]}
                    """));
        }
        var before = get(target.competition().getId(), owner, 200);
        var invalid = request(target.first().getId(), """
                {"news":[{"title":"교체","summary":"내용"}],"thoughts":[{"title":"%s","text":"내용"}]}
                """.formatted("가".repeat(256)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reportService.save(owner.getId(), target.competition().getId(), invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(get(target.competition().getId(), owner, 200)).isEqualTo(before);
    }

    @Test void rejectsMissingNonPositiveHumanAndOtherCompetitionParticipantOverHttp() throws Exception {
        for (String body : List.of("{\"news\":[],\"thoughts\":[]}",
                "{\"participant_id\":0,\"news\":[],\"thoughts\":[]}",
                "{\"participant_id\":-1,\"news\":[],\"thoughts\":[]}")) {
            postReport(competition.getId(), body, 400);
        }
        var human = participants.findAllByCompetitionIdOrderByIdAsc(competition.getId()).stream()
                .filter(p -> !p.getAccount().isAiManaged()).findFirst().orElseThrow();
        var other = multiAi();
        for (long id : List.of(human.getId(), other.first().getId(), Long.MAX_VALUE)) {
            var error = postReport(competition.getId(), "{\"participant_id\":" + id + ",\"news\":[],\"thoughts\":[]}", 404);
            assertThat((String) JsonPath.read(error, "$.code")).isEqualTo("AI_PARTICIPANT_NOT_FOUND");
        }
        assertThat((String) JsonPath.read(get(competition.getId(), owner, 200), "$.reports[0].report_state")).isEqualTo("pending");
    }

    private String postReport(long competitionId, String body, int expected) throws Exception {
        String base = "http://localhost:" + port;
        var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String cookie = csrf.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow().split(";", 2)[0];
        var now = Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer(jwtProperties.issuer())
                .subject(owner.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("type", "access").claim("actor", "AI").build();
        String token = jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/competitions/" + competitionId + "/report"))
                .timeout(Duration.ofSeconds(20)).header("Cookie", "access_token=" + token + "; " + cookie)
                .header(JsonPath.read(csrf.body(), "$.header_name"), JsonPath.read(csrf.body(), "$.token"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return response.body();
    }

    private AiFixture multiAi() {
        var target = competitions.save(new Competition(owner, "AI 대결", Competition.Type.AI_VS_AI,
                competition.getStartDate(), competition.getEndDate(), 10_000_000, 2));
        // 참가자 ID 순서와 표시 순서를 반대로 만들어 position 기준 정렬을 검증한다.
        var second = addAi(target, "AI2");
        var first = addAi(target, "AI1");
        agents.save(new com.stock_spoon.river_be.competition.entity.CompetitionAgent(second, 1, 1, List.of(), List.of(), "전략2"));
        agents.save(new com.stock_spoon.river_be.competition.entity.CompetitionAgent(first, 0, 1, List.of(), List.of(), "전략1"));
        return new AiFixture(target, first, second);
    }

    private record AiFixture(Competition competition,
            com.stock_spoon.river_be.competition.entity.CompetitionParticipant first,
            com.stock_spoon.river_be.competition.entity.CompetitionParticipant second) {}

    private com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest request(String body) {
        return request(ai.getId(), body);
    }

    private com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest request(long participantId, String body) {
        return objectMapper.readValue("{\"participant_id\":" + participantId + "," + body.strip().substring(1),
                com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest.class);
    }

    private long participantId(long competitionId) {
        return jdbc.queryForObject("select p.participant_id from competition_participants p join accounts a on a.account_id=p.account_id where p.competition_id=? and a.is_ai_managed=true", Long.class, competitionId);
    }

    private com.stock_spoon.river_be.competition.entity.CompetitionParticipant addAi(Competition target, String name) {
        var account = accounts.save(new com.stock_spoon.river_be.account.entity.Account(owner, name, 10_000_000, true));
        return participants.save(new com.stock_spoon.river_be.competition.entity.CompetitionParticipant(target, account));
    }

    private void analysis(long competitionId, String date, String title, String content) {
        jdbc.update("insert into competition_market_analyses (competition_id, participant_id, analysis_date, title, content) values (?, ?, ?, ?, ?)",
                competitionId, participantId(competitionId), LocalDate.parse(date), title, content);
        jdbc.update("update competition_participants set report_ready=true where competition_id=?", competitionId);
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
