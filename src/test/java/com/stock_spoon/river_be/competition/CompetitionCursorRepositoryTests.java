package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CompetitionCursorRepositoryTests {
    @Autowired CompetitionRepository competitions;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    private User owner;
    private static final Instant TIME = Instant.parse("2026-10-21T01:00:00Z");

    @BeforeEach
    void setup() { owner = users.save(new User("커서조회")); }

    @Test
    void readsFirstAndNextPagesUsingCreationTimeAndIdIncludingTies() {
        var oldest = create(owner, TIME.minusSeconds(60), false);
        var sameTimeFirst = create(owner, TIME, false);
        var sameTimeSecond = create(owner, TIME, false);
        var newest = create(owner, TIME.plusSeconds(60), false);
        var other = users.save(new User("다른 사용자"));
        create(other, TIME.plusSeconds(120), false);

        var first = competitions.findCursorPage(owner.getId(), null, null, null, PageRequest.of(0, 2));
        assertThat(first).extracting(Competition::getId).containsExactly(newest.getId(), sameTimeSecond.getId());
        var cursor = first.getLast();
        var next = competitions.findCursorPage(owner.getId(), null, cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, 2));
        assertThat(next).extracting(Competition::getId).containsExactly(sameTimeFirst.getId(), oldest.getId());
        var last = next.getLast();
        assertThat(competitions.findCursorPage(owner.getId(), null, last.getCreatedAt(), last.getId(), PageRequest.of(0, 2))).isEmpty();
    }

    @Test
    void filtersStateButCountsAllOngoingCompetitionsIndependentlyOfPage() {
        create(owner, TIME, false);
        var ongoingFirst = create(owner, TIME.plusSeconds(10), true);
        var ongoingSecond = create(owner, TIME.plusSeconds(20), true);
        var other = users.save(new User("타인 진행중"));
        create(other, TIME, true);

        var first = competitions.findCursorPage(owner.getId(), Competition.State.IN_PROGRESS, null, null, PageRequest.of(0, 1));
        assertThat(first).extracting(Competition::getId).containsExactly(ongoingSecond.getId());
        var cursor = first.getFirst();
        var next = competitions.findCursorPage(owner.getId(), Competition.State.IN_PROGRESS,
                cursor.getCreatedAt(), cursor.getId(), PageRequest.of(0, 1));
        assertThat(next).extracting(Competition::getId).containsExactly(ongoingFirst.getId());
        assertThat(competitions.countByUserIdAndState(owner.getId(), Competition.State.IN_PROGRESS)).isEqualTo(2);
        assertThat(competitions.findCursorPage(Long.MAX_VALUE, null, null, null, PageRequest.of(0, 1))).isEmpty();
        assertThat(competitions.countByUserIdAndState(Long.MAX_VALUE, Competition.State.IN_PROGRESS)).isZero();
    }

    @Test
    void continuesAfterNewInsertionAndCursorRowDeletionAndReadsLookahead() {
        var oldest = create(owner, TIME.minusSeconds(60), false);
        var middle = create(owner, TIME, false);
        var newest = create(owner, TIME.plusSeconds(60), false);
        // limit=2인 서비스가 3건을 요청하면 다음 페이지 존재를 판단할 여분 1건까지 조회한다.
        var first = competitions.findCursorPage(owner.getId(), null, null, null, PageRequest.of(0, 3));
        assertThat(first).extracting(Competition::getId).containsExactly(newest.getId(), middle.getId(), oldest.getId());
        var cursor = first.get(1);
        Instant cursorTime = cursor.getCreatedAt();
        Long cursorId = cursor.getId();
        create(owner, TIME.plusSeconds(120), false);
        competitions.deleteById(cursorId);
        competitions.flush();
        assertThat(competitions.findCursorPage(owner.getId(), null, cursorTime, cursorId, PageRequest.of(0, 3)))
                .extracting(Competition::getId).containsExactly(oldest.getId());
    }

    private Competition create(User user, Instant createdAt, boolean ongoing) {
        var competition = new Competition(user, "커서 대결", Competition.Type.USER_VS_AI,
                LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 25), 10_000_000, 1);
        if (ongoing) competition.start(TIME);
        competition = competitions.saveAndFlush(competition);
        // 자동 생성 시각 대신 정렬과 동률을 재현할 고정 시각을 테스트 DB에 지정한다.
        jdbc.update("update competitions set created_at = ? where competition_id = ?", java.sql.Timestamp.from(createdAt), competition.getId());
        entityManager.clear();
        return competitions.findById(competition.getId()).orElseThrow();
    }
}
