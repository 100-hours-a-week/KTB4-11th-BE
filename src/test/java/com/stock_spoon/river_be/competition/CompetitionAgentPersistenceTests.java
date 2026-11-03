package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class CompetitionAgentPersistenceTests {
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionParticipantRepository participants;
    @Autowired CompetitionAgentRepository agents;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired EntityManager em;

    @Test void strategyRoundTripsWithItsParticipantAndOrderedThemes() {
        var owner = users.save(new User("전략 저장"));
        var competition = competitions.save(new Competition(owner, "전략 저장",
                Competition.Type.USER_VS_AI, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 6), 3_000_000, 1));
        var participant = participants.save(new CompetitionParticipant(competition,
                accounts.save(new Account(owner, "전략 AI", 3_000_000, true))));
        var agent = agents.save(new CompetitionAgent(participant, 0, 1,
                List.of("반도체", "2차전지"), List.of("게임"), "장기 성장"));
        em.flush();
        em.clear();
        var stored = agents.findById(agent.getId()).orElseThrow();
        assertThat(stored.getParticipant().getId()).isEqualTo(participant.getId());
        assertThat(stored.getPosition()).isZero();
        assertThat(stored.getRiskLevel()).isEqualTo(1);
        assertThat(stored.getPreferredThemes()).containsExactly("반도체", "2차전지");
        assertThat(stored.getExcludedThemes()).containsExactly("게임");
        assertThat(stored.getAdditionalRequest()).isEqualTo("장기 성장");
    }

    @Test void participantCannotHaveTwoStrategies() {
        var owner = users.save(new User("전략 유일"));
        var competition = competitions.save(new Competition(owner, "전략 유일",
                Competition.Type.USER_VS_AI, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 6), 3_000_000, 1));
        var participant = participants.save(new CompetitionParticipant(competition,
                accounts.save(new Account(owner, "유일 AI", 3_000_000, true))));
        agents.saveAndFlush(new CompetitionAgent(participant, 0, 1, List.of(), List.of(), ""));
        assertThatThrownBy(() -> agents.saveAndFlush(new CompetitionAgent(participant, 1, 2, List.of(), List.of(), "")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
