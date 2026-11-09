package com.stock_spoon.river_be.competition;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.logging.CompetitionCreationLog;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.service.CompetitionAiSetupReservationService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitionAiSetupReservationLoggingTests {
    @ParameterizedTest
    @ValueSource(strings = {"ai_setup_strategy_lookup", "ai_setup_strategy_validation",
            "ai_setup_message_encode", "ai_setup_outbox_save"})
    void logsReservationFailureAtItsOwnStageWithoutPayload(String stage) {
        var agents = mock(CompetitionAgentRepository.class);
        var outboxes = mock(CompetitionAiSetupOutboxRepository.class);
        var json = spy(JsonMapper.builder().build());
        var competition = mock(Competition.class);
        when(competition.getId()).thenReturn(10L);
        var owner = mock(com.stock_spoon.river_be.user.entity.User.class);
        when(owner.getId()).thenReturn(1L);
        when(competition.getUser()).thenReturn(owner);
        when(competition.getAiParticipantCount()).thenReturn(1);
        var participant = mock(CompetitionParticipant.class);
        var account = mock(com.stock_spoon.river_be.account.entity.Account.class);
        when(participant.getId()).thenReturn(20L);
        when(participant.getAccount()).thenReturn(account);
        when(account.getId()).thenReturn(30L);
        var agent = new CompetitionAgent(participant, 0, 3, List.of("private-theme"), List.of(), "private-request");
        when(agents.findAllByParticipantCompetitionIdOrderByPositionAsc(10L)).thenReturn(List.of(agent));
        var error = new IllegalStateException("private-failure");
        switch (stage) {
            case "ai_setup_strategy_lookup" -> when(agents.findAllByParticipantCompetitionIdOrderByPositionAsc(10L)).thenThrow(error);
            case "ai_setup_strategy_validation" -> when(competition.getAiParticipantCount()).thenReturn(2);
            case "ai_setup_message_encode" -> doThrow(error).when(json).writeValueAsString(any());
            case "ai_setup_outbox_save" -> doThrow(error).when(outboxes).save(any(CompetitionAiSetupOutbox.class));
            default -> throw new AssertionError(stage);
        }
        var logger = (Logger) LoggerFactory.getLogger(CompetitionCreationLog.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            try (var trace = new CompetitionCreationLog()) {
                CompetitionCreationLog.competitionId(10L);
                CompetitionCreationLog.stage("ai_strategy_save");
                var thrown = catchThrowableOfType(() -> new CompetitionAiSetupReservationService(agents, outboxes, json)
                        .reserve(competition), IllegalStateException.class);
                assertThat(thrown).isNotNull();
                if (!stage.equals("ai_setup_strategy_validation")) assertThat(thrown).isSameAs(error);
                trace.failure(thrown);
                assertThat(logs.list).hasSize(1);
                assertThat(logs.list.getFirst().getFormattedMessage())
                        .contains("event=competition_creation_failed", "stage=" + stage, "competitionId=10")
                        .doesNotContain("stage=ai_strategy_save", "private-theme", "private-request", "private-failure");
            }
            assertThat(MDC.get("competitionCreateStage")).isNull();
            if (!stage.equals("ai_setup_outbox_save")) verifyNoInteractions(outboxes);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            MDC.clear();
        }
    }
}
