package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.messaging.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitionAiSetupMessageTests {
    @Test void serializesAgreedSnakeCaseContract() {
        var message = new CompetitionAiSetupRequest("stable-id", "competition_ai_setup", 1L,
                List.of(new CompetitionAiSetupRequest.Agent(201L, 301L, 5, List.of("반도체"), List.of(), "")));
        var node = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(message));
        assertThat(node.get("event_id").asText()).isEqualTo("stable-id");
        assertThat(node.get("event_type").asText()).isEqualTo("competition_ai_setup");
        assertThat(node.get("agents").get(0).get("participant_id").asLong()).isEqualTo(201);
        assertThat(node.get("agents").get(0).get("account_id").asLong()).isEqualTo(301);
        assertThat(node.get("agents").get(0).get("risk_level").asInt()).isEqualTo(5);
        assertThat(node.has("eventId")).isFalse();
    }
    @Test void sendsFrozenBodyWithoutFifoFields() {
        var sqs = mock(SqsAsyncClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                SendMessageResponse.builder().messageId("message").build()));
        new CompetitionAiSetupRequestPublisher(sqs, "queue").publish("{\"event_id\":\"stable\"}");
        var capture = org.mockito.ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(capture.capture());
        assertThat(capture.getValue().messageBody()).isEqualTo("{\"event_id\":\"stable\"}");
        assertThat(capture.getValue().messageGroupId()).isNull();
        assertThat(capture.getValue().messageDeduplicationId()).isNull();
    }
    @Test void failedPublishIsNotReportedAsSuccess() {
        var sqs = mock(SqsAsyncClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThatThrownBy(() -> new CompetitionAiSetupRequestPublisher(sqs,"queue").publish("{}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
