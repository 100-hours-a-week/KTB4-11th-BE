package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.dto.*;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.*;
import jakarta.persistence.EntityManager;
import java.time.ZonedDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Opt-in fixture only: fixed loopback port/schema, never an application/production datasource. */
@EnabledIfEnvironmentVariable(named = "AI_MYSQL_VALIDATION", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:33379/stockspoon_ai_validation?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=root", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompetitionAiMySqlMigrationTests {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @Autowired AccountRepository accounts;
    @MockitoSpyBean CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @Autowired UserRepository users;
    @Autowired UserDeletionRepository deletion;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @MockitoSpyBean CompetitionTradingHours hours;
    final ZonedDateTime now = ZonedDateTime.parse("2026-10-12T10:00:00+09:00[Asia/Seoul]");
    Long legacyUserId, legacyCompetitionId;

    @BeforeAll void applyMigrationToLegacySchemaWithExistingRows() throws Exception {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("stockspoon_ai_validation");
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4.");
        doReturn(now).when(hours).now();
        var legacy = owner(); legacyUserId = legacy.getId();
        var request = request("기존 사용자 대결", 1);
        ReflectionTestUtils.setField(request, "competitionType", "user_vs_ai");
        ReflectionTestUtils.setField(request, "agents", null);
        legacyCompetitionId = service.createCompetition(legacyUserId, request).getCompetitionId();
        // JPA supplies unrelated application tables; restore the old competition shape before applying real SQL.
        jdbc.execute("drop table competition_agent_excluded_themes");
        jdbc.execute("drop table competition_agent_preferred_themes");
        jdbc.execute("drop table competition_agents");
        jdbc.execute("alter table competitions modify competition_type enum('USER_VS_AI') not null");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/competition-ai-settings.sql"));
        }
    }

    @BeforeEach void restoreClockAfterMockitoReset() {
        doReturn(now).when(hours).now();
    }

    @Test void migrationPreservesLegacyDataAndAddsOnlyRequiredTablesAndType() {
        assertThat(jdbc.queryForObject("select competition_type from competitions where competition_id=?", String.class,
                legacyCompetitionId)).isEqualTo("USER_VS_AI");
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(legacyCompetitionId)).hasSize(2);
        assertThat(jdbc.queryForObject("select column_type from information_schema.columns where table_schema=database() "
                + "and table_name='competitions' and column_name='competition_type'", String.class)).contains("AI_VS_AI");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='competitions' and column_name='ai_settings_complete'", Integer.class)).isZero();
    }

    @Test void jpaWritesAndReadsFourStrategiesUsingTheMigratedTables() {
        var response = service.createCompetition(owner().getId(), request("MySQL 네 전략", 4));
        new TransactionTemplate(manager).executeWithoutResult(s -> {
            var stored = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(response.getCompetitionId());
            assertThat(stored).hasSize(4);
            assertThat(stored).extracting(CompetitionAgent::getRiskLevel).containsExactly(1, 2, 3, 5);
            assertThat(stored.getFirst().getPreferredThemes()).containsExactly("반도체 🚀", "2차전지");
            assertThat(stored.getFirst().getAdditionalRequest()).isEqualTo("장기 성장");
            assertThat(stored).allSatisfy(a -> {
                assertThat(a.getParticipant().getAccount().getInitialCapital()).isEqualTo(3_000_000);
                assertThat(a.getParticipant().getAccount().getCashBalance()).isEqualTo(3_000_000);
            });
        });
        assertThat(response.getState()).isEqualTo("ready");
    }

    @Test void mysqlEnforcesRiskAndUniqueParticipantConstraints() {
        var id = service.createCompetition(owner().getId(), request("MySQL 제약", 2)).getCompetitionId();
        var agent = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(id).getFirst();
        assertThatThrownBy(() -> jdbc.update("update competition_agents set risk_level=0 where agent_id=?", agent.getId()))
                .isInstanceOfSatisfying(org.springframework.jdbc.UncategorizedSQLException.class,
                        error -> assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("insert into competition_agents(participant_id,agent_position,risk_level,additional_request) "
                + "select participant_id,agent_position,risk_level,additional_request from competition_agents where agent_id=?", agent.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void lastParticipantFailureRollsBackEveryNewRowOnMysql() {
        var user = owner();
        long[] before = counts();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("MySQL 마지막 저장 실패");
            var participant = call.getArgument(0, CompetitionParticipant.class);
            em.persist(participant); return participant;
        }).when(participants).save(any(CompetitionParticipant.class));
        try {
            assertThatThrownBy(() -> service.createCompetition(user.getId(), request("MySQL 롤백", 2)))
                    .isInstanceOf(IllegalStateException.class);
        } finally { reset(participants); }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(counts()).containsExactly(before);
    }

    @Test void withdrawalDeletionRemovesThemesBeforeAgentsAndKeepsLegacyOwner() {
        var owner = owner();
        var id = service.createCompetition(owner.getId(), request("MySQL 탈퇴", 2)).getCompetitionId();
        var ids = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(id).stream().map(CompetitionAgent::getId).toList();
        new TransactionTemplate(manager).executeWithoutResult(s -> deletion.deleteAllForUser(owner.getId()));
        assertThat(users.findById(owner.getId())).isEmpty();
        assertThat(competitions.findById(id)).isEmpty();
        assertThat(agents.findAllById(ids)).isEmpty();
        for (Long agentId : ids) {
            assertThat(jdbc.queryForObject("select count(*) from competition_agent_preferred_themes where agent_id=?", Integer.class, agentId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from competition_agent_excluded_themes where agent_id=?", Integer.class, agentId)).isZero();
        }
        assertThat(users.findById(legacyUserId)).isPresent();
        assertThat(competitions.findById(legacyCompetitionId)).isPresent();
    }

    User owner() { var owner = new User("MySQL 검증"); owner.completeOnboarding(); return users.save(owner); }
    CompetitionCreateRequest request(String name, int count) {
        var request = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(request, "competitionName", name);
        ReflectionTestUtils.setField(request, "competitionType", "ai_vs_ai");
        ReflectionTestUtils.setField(request, "startDate", now.toLocalDate());
        ReflectionTestUtils.setField(request, "endDate", now.toLocalDate().plusDays(30));
        ReflectionTestUtils.setField(request, "initialCapital", 3_000_000L);
        ReflectionTestUtils.setField(request, "aiParticipantCount", count);
        ReflectionTestUtils.setField(request, "agents", java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new CompetitionAgentRequest(i == 3 ? 5 : i + 1,
                        List.of("반도체 🚀", "2차전지"), List.of("게임"), "장기 성장")).toList());
        return request;
    }
    long[] counts() { return new long[]{competitions.count(), accounts.count(), participants.count(), agents.count(),
            jdbc.queryForObject("select count(*) from competition_agent_preferred_themes", Long.class),
            jdbc.queryForObject("select count(*) from competition_agent_excluded_themes", Long.class)}; }
}
