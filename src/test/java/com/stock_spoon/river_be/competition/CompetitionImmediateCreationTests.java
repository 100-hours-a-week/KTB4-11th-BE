package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.dto.*;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.*;
import com.stock_spoon.river_be.competition.messaging.CompetitionAiSetupRequestPublisher;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.ZonedDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"competition.ai-setup.queue.enabled=true",
        "competition.ai-setup.dispatch.enabled=false",
        "competition.ai-setup.queue.url=https://sqs.ap-northeast-2.amazonaws.com/123/test"})
class CompetitionImmediateCreationTests {
    @Autowired CompetitionCreationCoordinator coordinator;
    @Autowired CompetitionRepository competitions;
    @Autowired CompetitionAiSetupOutboxRepository outboxes;
    @Autowired UserRepository users;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean CompetitionAiSetupRequestPublisher publisher;
    @MockitoSpyBean CompetitionTradingHours hours;
    User owner;
    final ZonedDateTime now = ZonedDateTime.parse("2026-11-11T10:00:00+09:00[Asia/Seoul]");
    @BeforeEach void setup() {
        doReturn(now).when(hours).now();
        owner = new User("즉시 시작"); owner.completeOnboarding(); owner = users.save(owner);
    }
    @ParameterizedTest @ValueSource(ints = {2,3,4})
    void startsImmediatelyAfterCommittedCreationAndDispatch(int count) {
        doAnswer(call -> {
            // 외부 발행 시점에는 생성 트랜잭션이 이미 끝나 있어야 한다.
            var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(call.getArgument(0, String.class));
            long competitionId = payload.get("competition_id").asLong();
            var independent = new org.springframework.transaction.support.TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Boolean visible = independent.execute(status -> competitions.existsById(competitionId));
            assertThat(visible).isTrue();
            return null;
        }).when(publisher).publish(anyString());
        var result = coordinator.create(owner.getId(), request(count));
        assertThat(result.getState()).isEqualTo("in_progress");
        assertThat(competitions.findById(result.getCompetitionId()).orElseThrow().getStartedAt()).isEqualTo(now.toInstant());
        assertThat(event(result).getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.SENT);
    }
    @Test void publishFailurePreservesReadyAndPending() {
        doThrow(new IllegalStateException("offline")).when(publisher).publish(anyString());
        var result = coordinator.create(owner.getId(), request(2));
        assertThat(result.getState()).isEqualTo("ready");
        assertThat(event(result).getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.PENDING);
    }
    @Test void marketClosureWhilePublishingKeepsSentButReady() {
        doAnswer(call -> { doReturn(now.withHour(15).withMinute(30)).when(hours).now(); return null; })
                .when(publisher).publish(anyString());
        var result = coordinator.create(owner.getId(), request(2));
        assertThat(result.getState()).isEqualTo("ready");
        assertThat(event(result).getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.SENT);
    }
    @Test void outsideMarketDoesNotPublishImmediately() {
        doReturn(now.withHour(8)).when(hours).now();
        var result = coordinator.create(owner.getId(), request(2));
        assertThat(result.getState()).isEqualTo("ready");
        verifyNoInteractions(publisher);
    }
    CompetitionAiSetupOutbox event(CompetitionCreateResponse result) {
        return outboxes.findAll().stream().filter(o -> o.getCompetition().getId().equals(result.getCompetitionId())).findFirst().orElseThrow();
    }
    CompetitionCreateRequest request(int count) {
        var r = new CompetitionCreateRequest();
        ReflectionTestUtils.setField(r,"competitionType","ai_vs_ai");
        ReflectionTestUtils.setField(r,"competitionName","즉시 " + UUID.randomUUID().toString().substring(0,8));
        ReflectionTestUtils.setField(r,"startDate",now.toLocalDate());
        ReflectionTestUtils.setField(r,"endDate",now.toLocalDate().plusDays(5));
        ReflectionTestUtils.setField(r,"initialCapital",3_000_000L);
        ReflectionTestUtils.setField(r,"aiParticipantCount",count);
        ReflectionTestUtils.setField(r,"agents",java.util.stream.IntStream.range(0,count)
                .mapToObj(i -> new CompetitionAgentRequest(i+1,List.of("반도체"),List.of(),"")).toList());
        return r;
    }
}
