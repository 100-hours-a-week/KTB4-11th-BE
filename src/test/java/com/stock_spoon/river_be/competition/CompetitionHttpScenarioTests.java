package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.auth.token.JwtTokenProvider;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** MockMvc 대신 실제 HTTP 포트에 순서대로 요청하는 대결 생명주기 시나리오. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompetitionHttpScenarioTests {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionDailyPerformanceRepository performances;
    @Autowired CompetitionCompletionService completion;
    @Autowired CompetitionDailyPerformanceService daily;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean CompetitionTradingHours hours;
    @MockitoBean KiwoomMarketClient market;
    private final HttpClient client = HttpClient.newHttpClient();
    private final StringBuilder transcript = new StringBuilder("# 대결 실제 HTTP 호출 결과\n\n테스트 H2 DB / 테스트 JWT 사용. 외부 로그인은 사전 인증으로 대체.\n시간만 조정하고 실제 시작·마감 작업 메서드를 실행한다. 매매 없는 계좌로 외부 시세 호출은 없다.\n\n");
    private String cookie;
    private String csrf;
    private static final String BASE = "/api/v2/users/me/competitions";

    @Test
    void createWaitStartRecordAndCompleteThroughHttp() throws Exception {
        var user = new User("HTTP시나리오");
        user.completeOnboarding();
        user = users.save(user);
        cookie = "access_token=" + tokens.issue(user.getId()).accessToken();
        at("2026-10-16T21:00:00+09:00");
        var csrfResponse = call("GET", "/api/v1/auth/csrf", null, null, 200);
        csrf = JsonPath.read(csrfResponse.body(), "$.token");
        cookie += "; " + csrfResponse.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        call("GET", BASE, null, null, 401);
        var body = """
                {"competition_name":"HTTP 대결","competition_type":"user_vs_ai",
                 "start_date":"2026-10-16","end_date":"2026-10-20",
                 "initial_capital":10000000,"ai_participant_count":1}
                """;
        var created = call("POST", BASE, body, cookie, 201);
        Number id = JsonPath.read(created.body(), "$.competition_id");
        String path = BASE + "/" + id;
        var list = call("GET", BASE, null, cookie, 200);
        assertThat((String) JsonPath.read(list.body(), "$.competitions[0].state")).isEqualTo("ready");
        call("POST", BASE, body, cookie, 409);
        var detail = call("GET", path, null, cookie, 200);
        assertThat((java.util.List<?>) JsonPath.read(detail.body(), "$.daily_performances")).isEmpty();
        call("GET", path + "/result", null, cookie, 409);
        var other = users.save(new User("HTTP타인"));
        call("GET", path, null, "access_token=" + tokens.issue(other.getId()).accessToken(), 404);

        at("2026-10-19T10:00:00+09:00");
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                new CompetitionStartScheduler(competitions, hours).startWaitingCompetitions());
        detail = call("GET", path, null, cookie, 200);
        assertThat((String) JsonPath.read(detail.body(), "$.state")).isEqualTo("in_progress");
        call("GET", path + "/result", null, cookie, 409);

        var job = new CompetitionCompletionScheduler(competitions, completion, hours, daily);
        at("2026-10-19T16:00:00+09:00");
        job.completeExpiredCompetitions();
        at("2026-10-20T12:00:00+09:00");
        detail = call("GET", path, null, cookie, 200);
        assertThat((String) JsonPath.read(detail.body(), "$.daily_performances[0].date")).isEqualTo("2026-10-19");
        assertThat((java.util.List<?>) JsonPath.read(detail.body(), "$.daily_performances")).hasSize(1);

        at("2026-10-20T16:00:00+09:00");
        job.completeExpiredCompetitions();
        var result = call("GET", path + "/result", null, cookie, 200);
        assertThat((String) JsonPath.read(result.body(), "$.state")).isEqualTo("completed");
        assertThat((String) JsonPath.read(result.body(), "$.winner")).isEqualTo("draw");
        assertThat((java.util.List<?>) JsonPath.read(result.body(), "$.daily_performances")).hasSize(2);
        list = call("GET", BASE, null, cookie, 200);
        assertThat((Number) JsonPath.read(list.body(), "$.ongoing_competition_count")).isEqualTo(0);
        long count = performances.count();
        job.completeExpiredCompetitions();
        assertThat(performances.count()).isEqualTo(count);
        assertThat(call("GET", path + "/result", null, cookie, 200).body()).isEqualTo(result.body());
        verifyNoInteractions(market);
        var report = Path.of("build/reports/competition-http-scenario.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, transcript);
    }

    private void at(String dateTime) {
        doReturn(ZonedDateTime.parse(dateTime)).when(hours).now();
        transcript.append("테스트 시각: ").append(dateTime).append("\n\n");
    }

    private HttpResponse<String> call(String method, String path, String body, String auth, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (auth != null) request.header("Cookie", auth);
        if (body != null) request.header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        transcript.append("## ").append(method).append(" ").append(path).append(" → ").append(response.statusCode()).append("\n\n");
        if (body != null) transcript.append("요청\n```json\n").append(body).append("\n```\n\n");
        if (!path.endsWith("/csrf")) transcript.append("응답\n```json\n").append(response.body()).append("\n```\n\n");
        assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expected);
        if (method.equals("GET") && path.startsWith(BASE) && expected == 200) {
            if (path.equals(BASE)) {
                java.util.List<java.util.Map<String, Object>> items = JsonPath.read(response.body(), "$.competitions");
                assertThat(items).allSatisfy(item -> assertThat(item).containsKey("d_day").doesNotContainKeys("DDay", "remainingDays"));
            } else {
                java.util.Map<String, Object> item = JsonPath.read(response.body(), "$");
                assertThat(item).containsKey("d_day").doesNotContainKeys("DDay", "remainingDays");
            }
        }
        return response;
    }
}
