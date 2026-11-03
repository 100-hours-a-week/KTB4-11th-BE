package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionReportOutbox;
import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
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
class CompetitionReportOutboxPersistenceTests {
    @Autowired CompetitionReportOutboxRepository outboxes;
    @Autowired CompetitionRepository competitions;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    @Test
    void savesAndReloadsPendingRequestWithStableId() {
        var competition = competition();
        String requestId = UUID.randomUUID().toString();
        var saved = outboxes.saveAndFlush(new CompetitionReportOutbox(competition, requestId));
        Long id = saved.getId();
        entityManager.clear();

        var reloaded = outboxes.findById(id).orElseThrow();
        assertThat(reloaded.getCompetition().getId()).isEqualTo(competition.getId());
        assertThat(reloaded.getRequestId()).isEqualTo(requestId);
        assertThat(reloaded.getStatus()).isEqualTo(CompetitionReportOutbox.Status.PENDING);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void sentStatusIsPersistedWithoutChangingRequestId() {
        String requestId = UUID.randomUUID().toString();
        var saved = outboxes.saveAndFlush(new CompetitionReportOutbox(competition(), requestId));
        saved.markSent();
        entityManager.flush();
        entityManager.clear();

        var reloaded = outboxes.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CompetitionReportOutbox.Status.SENT);
        assertThat(reloaded.getRequestId()).isEqualTo(requestId);
    }

    @Test
    void pendingQueryExcludesSentRequestsAndReadsOldestFirstWithinBatchSize() {
        var first = outboxes.saveAndFlush(new CompetitionReportOutbox(competition(), UUID.randomUUID().toString()));
        var sent = outboxes.saveAndFlush(new CompetitionReportOutbox(competition(), UUID.randomUUID().toString()));
        sent.markSent();
        var last = outboxes.saveAndFlush(new CompetitionReportOutbox(competition(), UUID.randomUUID().toString()));
        entityManager.clear();

        var batch = outboxes.findByStatusOrderByIdAsc(CompetitionReportOutbox.Status.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(batch).extracting(CompetitionReportOutbox::getId).containsExactly(first.getId());
        var allPending = outboxes.findByStatusOrderByIdAsc(CompetitionReportOutbox.Status.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(allPending).extracting(CompetitionReportOutbox::getId).containsExactly(first.getId(), last.getId());
    }

    @Test
    void rejectsSecondInitialRequestForSameCompetition() {
        var competition = competition();
        outboxes.saveAndFlush(new CompetitionReportOutbox(competition, UUID.randomUUID().toString()));
        assertThatThrownBy(() -> outboxes.saveAndFlush(
                new CompetitionReportOutbox(competition, UUID.randomUUID().toString())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSameRequestIdForDifferentCompetitions() {
        String requestId = UUID.randomUUID().toString();
        outboxes.saveAndFlush(new CompetitionReportOutbox(competition(), requestId));
        var other = competition();
        assertThatThrownBy(() -> outboxes.saveAndFlush(new CompetitionReportOutbox(other, requestId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Competition competition() {
        var owner = users.save(new User("아웃박스" + UUID.randomUUID().toString().substring(0, 5)));
        return competitions.saveAndFlush(new Competition(owner, "테스트 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), 10_000_000, 1));
    }
}
