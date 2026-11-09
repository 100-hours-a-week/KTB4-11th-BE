package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.repository.*;
import com.stock_spoon.river_be.competition.messaging.*;
import com.stock_spoon.river_be.competition.service.*;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CompetitionAiSetupDispatchTests {
    @Test void retryRetainsEventAndPayloadThenSkipsSent() {
        var c = mock(Competition.class);
        when(c.getId()).thenReturn(1L);
        when(c.getCompetitionType()).thenReturn(Competition.Type.AI_VS_AI);
        when(c.getState()).thenReturn(Competition.State.READY);
        var o = new CompetitionAiSetupOutbox(c,"stable-id","frozen-json");
        var competitions = mock(CompetitionRepository.class);
        var outboxes = mock(CompetitionAiSetupOutboxRepository.class);
        var publisher = mock(CompetitionAiSetupRequestPublisher.class);
        when(competitions.findLockedById(1L)).thenReturn(Optional.of(c));
        when(outboxes.findById(2L)).thenReturn(Optional.of(o));
        when(outboxes.findLockedById(2L)).thenReturn(Optional.of(o));
        var service = new CompetitionAiSetupDispatchService(mock(EntityManager.class), competitions,outboxes,publisher);
        doThrow(new IllegalStateException("offline")).doNothing().when(publisher).publish("frozen-json");
        assertThatThrownBy(() -> service.dispatch(2L)).isInstanceOf(IllegalStateException.class);
        assertThat(o.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.PENDING);
        assertThat(service.dispatch(2L)).isEqualTo("stable-id");
        assertThat(o.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.SENT);
        assertThat(service.dispatch(2L)).isNull();
        verify(publisher,times(2)).publish("frozen-json");
    }
    @Test void cancelledCompetitionNeverPublishes() {
        var c = mock(Competition.class);
        when(c.getId()).thenReturn(1L);
        when(c.getState()).thenReturn(Competition.State.CANCELLED);
        var o = new CompetitionAiSetupOutbox(c,"id","{}");
        var competitions = mock(CompetitionRepository.class);
        var outboxes = mock(CompetitionAiSetupOutboxRepository.class);
        var publisher = mock(CompetitionAiSetupRequestPublisher.class);
        when(competitions.findLockedById(1L)).thenReturn(Optional.of(c));
        when(outboxes.findById(2L)).thenReturn(Optional.of(o));
        when(outboxes.findLockedById(2L)).thenReturn(Optional.of(o));
        new CompetitionAiSetupDispatchService(mock(EntityManager.class),competitions,outboxes,publisher).dispatch(2L);
        assertThat(o.getStatus()).isEqualTo(CompetitionAiSetupOutbox.Status.CANCELLED);
        verifyNoInteractions(publisher);
    }
}
