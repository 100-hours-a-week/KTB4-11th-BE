package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CompetitionPersistenceTests {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    @Test
    void readsOnlyUserParticipantsInRequestedCompetitions() {
        var owner = users.save(new User("페이지 사용자"));
        var other = users.save(new User("다른 사용자"));
        var selected = saveCompetition(owner, "선택 대결");
        var outside = saveCompetition(owner, "다음 페이지");
        var foreign = saveCompetition(other, "타인 대결");
        var expected = participants.save(new CompetitionParticipant(selected,
                accounts.save(new Account(owner, "선택 사용자", 10_000_000, false))));
        participants.save(new CompetitionParticipant(selected,
                accounts.save(new Account(owner, "선택 AI", 10_000_000, true))));
        participants.save(new CompetitionParticipant(outside,
                accounts.save(new Account(owner, "페이지 밖", 10_000_000, false))));
        participants.save(new CompetitionParticipant(foreign,
                accounts.save(new Account(other, "타인 계좌", 10_000_000, false))));
        entityManager.flush();
        entityManager.clear();

        var result = participants.findUserParticipantsInCompetitions(owner.getId(),
                java.util.List.of(selected.getId(), foreign.getId()));

        assertThat(result).extracting(CompetitionParticipant::getId).containsExactly(expected.getId());
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(result.getFirst(), "account")).isTrue();
        assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(result.getFirst(), "competition")).isTrue();
    }

    private Competition saveCompetition(User owner, String name) {
        return competitions.save(new Competition(owner, name, Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 10_000_000, 1));
    }

    @Test
    void savesAndReloadsCompetitionWithItsTwoDedicatedAccounts() {
        var user = users.save(new User("대결테스트"));
        var competition = competitions.save(new Competition(user, "반도체 집중",
                Competition.Type.USER_VS_AI, LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 18), 10_000_000, 1));
        var first = accounts.save(new Account(user, "반도체 집중", 10_000_000));
        var second = accounts.save(new Account(user, "대결AI-" + competition.getId(), 10_000_000));
        participants.save(new CompetitionParticipant(competition, first));
        participants.save(new CompetitionParticipant(competition, second));
        entityManager.flush();
        entityManager.clear();

        var loaded = competitions.findById(competition.getId()).orElseThrow();
        assertThat(loaded.getState()).isEqualTo(Competition.State.READY);
        assertThat(loaded.getStartedAt()).isNull();
        assertThat(loaded.getAiParticipantCount()).isEqualTo(1);
        assertThat(loaded.getInitialCapital()).isEqualTo(10_000_000);
        assertThat(participants.findAllByCompetitionIdOrderByIdAsc(loaded.getId()))
                .extracting(p -> p.getAccount().getId()).containsExactly(first.getId(), second.getId());
        assertThat(participants.findByAccountId(second.getId()).orElseThrow()
                .getCompetition().getId()).isEqualTo(loaded.getId());
        assertThat(participants.existsByAccountId(first.getId())).isTrue();

        var now = Instant.parse("2026-10-16T00:00:00Z");
        loaded.start(now);
        entityManager.flush();
        entityManager.clear();
        var started = competitions.findById(loaded.getId()).orElseThrow();
        assertThat(started.getState()).isEqualTo(Competition.State.IN_PROGRESS);
        assertThat(started.getStartedAt()).isEqualTo(now);
        assertThatThrownBy(() -> started.start(now)).isInstanceOf(IllegalStateException.class);
    }
}
