package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
class CompetitionAiCreationTests {
    @Autowired CompetitionService service;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired UserRepository users;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.stock_spoon.river_be.account.service.AccountService accountService;
    @MockitoSpyBean CompetitionTradingHours hours;
    User owner;
    final ZonedDateTime now = ZonedDateTime.parse("2026-11-03T10:00:00+09:00[Asia/Seoul]");

    @BeforeEach void setup() {
        doReturn(now).when(hours).now();
        owner = new User("생성 테스트");
        owner.completeOnboarding();
        owner = users.save(owner);
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4})
    void createsConfiguredAiCompetitionAndDoesNotStart(int count) {
        var response = service.createCompetition(owner.getId(), request("전략 대결", count));
        assertThat(response.getState()).isEqualTo("ready");
        var competition = competitions.findById(response.getCompetitionId()).orElseThrow();
        assertThat(competition.getCompetitionType().name()).isEqualTo("AI_VS_AI");
        assertThat(competition.getAiParticipantCount()).isEqualTo(count);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(competition.getId())).hasSize(count);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s ->
                new CompetitionStartScheduler(competitions, hours).startWaitingCompetitions());
        assertThat(competitions.findById(competition.getId()).orElseThrow().getState()).isEqualTo(Competition.State.READY);
        assertThatThrownBy(() -> competition.start(now.toInstant())).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 5})
    void rejectsInvalidAiCounts(int count) {
        var invalidCount = request("인원 오류", count);
        ReflectionTestUtils.setField(invalidCount, "agents", java.util.List.of(
                new com.stock_spoon.river_be.competition.dto.CompetitionAgentRequest(3, java.util.List.of(), java.util.List.of(), ""),
                new com.stock_spoon.river_be.competition.dto.CompetitionAgentRequest(3, java.util.List.of(), java.util.List.of(), "")));
        assertThatThrownBy(() -> service.createCompetition(owner.getId(), invalidCount))
                .isInstanceOfSatisfying(CompetitionException.class,
                        e -> assertThat(e.getCode()).isEqualTo(count < 1 || count > 4 ? "INVALID_REQUEST" : "INVALID_AI_PARTICIPANT_COUNT"));
    }

    @Test void competitionNameIsReservedEvenWhenAiAccountsUseInternalNames() {
        service.createCompetition(owner.getId(), request("예약 이름", 2));
        assertThat(service.checkNameAvailability(owner.getId(), "예약 이름").isAvailable()).isFalse();
        assertThatThrownBy(() -> service.createCompetition(owner.getId(), request(" 예약 이름 ", 3)))
                .isInstanceOfSatisfying(CompetitionException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DUPLICATE_ACCOUNT_NAME"));
        assertThatThrownBy(() -> accountService.create(owner.getId(),
                new com.stock_spoon.river_be.account.dto.AccountCreateRequest("예약 이름", 3_000_000L)))
                .isInstanceOf(com.stock_spoon.river_be.account.exception.AccountException.class);
    }

    @Test void nullCountIsAValidationErrorInsteadOfUnboxingFailure() {
        var request = request("누락 인원", 2);
        ReflectionTestUtils.setField(request, "aiParticipantCount", null);
        assertThatThrownBy(() -> service.createCompetition(owner.getId(), request))
                .isInstanceOfSatisfying(CompetitionException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test void reservedNameAlsoAppliesToAccountRenameAndAutomaticNames() {
        service.createCompetition(owner.getId(), request("기본 계좌 1", 2));
        var created = accountService.create(owner.getId(),
                new com.stock_spoon.river_be.account.dto.AccountCreateRequest(null, 3_000_000L));
        assertThat(created.accountName()).isEqualTo("기본 계좌 2");
        assertThatThrownBy(() -> accountService.rename(owner.getId(), created.accountId(),
                new com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest("기본 계좌 1")))
                .isInstanceOf(com.stock_spoon.river_be.account.exception.AccountException.class);
    }

    CompetitionCreateRequest request(String name, int count) {
        var request = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(request, "competitionName", name);
        ReflectionTestUtils.setField(request, "competitionType", "ai_vs_ai");
        ReflectionTestUtils.setField(request, "startDate", now.toLocalDate());
        ReflectionTestUtils.setField(request, "endDate", now.toLocalDate().plusDays(30));
        ReflectionTestUtils.setField(request, "initialCapital", 3_000_000L);
        ReflectionTestUtils.setField(request, "aiParticipantCount", count);
        ReflectionTestUtils.setField(request, "agents", java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new com.stock_spoon.river_be.competition.dto.CompetitionAgentRequest(
                        3, java.util.List.of(), java.util.List.of(), "")).toList());
        return request;
    }
}
