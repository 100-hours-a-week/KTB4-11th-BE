package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionAiSetupOutbox;
import com.stock_spoon.river_be.competition.repository.CompetitionAiSetupOutboxRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CompetitionAiSetupOutboxPersistenceTests {
    @Autowired CompetitionAiSetupOutboxRepository outboxes;
    @Autowired CompetitionRepository competitions;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    @Test
    void savesAndReloadsPendingRequestWithStableId() {
        var competition = competition();
        String requestId = UUID.randomUUID().toString();
        var saved = outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition, requestId, "{}"));
        Long id = saved.getId();
        entityManager.clear();

        var reloaded = outboxes.findById(id).orElseThrow();
        assertThat(reloaded.getCompetition().getId()).isEqualTo(competition.getId());
        assertThat(reloaded.getEventId()).isEqualTo(requestId);
        assertThat(reloaded.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sentStatusIsPersistedWithoutChangingRequestId() {
        String requestId = UUID.randomUUID().toString();
        var saved = outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition(), requestId, "{}"));
        saved.markSent();
        entityManager.flush();
        entityManager.clear();

        var reloaded = outboxes.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.SENT);
        assertThat(reloaded.getEventId()).isEqualTo(requestId);
    }

    @Test
    void pendingQueryExcludesSentRequestsAndReadsOldestFirstWithinBatchSize() {
        var first = outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition(), UUID.randomUUID().toString(), "{}"));
        var sent = outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition(), UUID.randomUUID().toString(), "{}"));
        sent.markSent();
        var last = outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition(), UUID.randomUUID().toString(), "{}"));
        entityManager.clear();

        var batch = outboxes.findPendingIdsBetween(0, outboxes.findPendingUpperBound(),
                org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(batch).containsExactly(first.getId());
        var allPending = outboxes.findPendingIdsBetween(0, outboxes.findPendingUpperBound(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(allPending).containsExactly(first.getId(), last.getId());
    }

    @Test
    void rejectsSecondInitialRequestForSameCompetition() {
        var competition = competition();
        outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition, UUID.randomUUID().toString(), "{}"));
        assertThatThrownBy(() -> outboxes.saveAndFlush(
                new CompetitionAiSetupOutbox(competition, UUID.randomUUID().toString(), "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSameRequestIdForDifferentCompetitions() {
        String requestId = UUID.randomUUID().toString();
        outboxes.saveAndFlush(new CompetitionAiSetupOutbox(competition(), requestId, "{}"));
        var other = competition();
        assertThatThrownBy(() -> outboxes.saveAndFlush(new CompetitionAiSetupOutbox(other, requestId, "{}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Competition competition() {
        var owner = users.save(new User("아웃박스" + UUID.randomUUID().toString().substring(0, 5)));
        return competitions.saveAndFlush(new Competition(owner, "테스트 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 10_000_000, 1));
    }
}
