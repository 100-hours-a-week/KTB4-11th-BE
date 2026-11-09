package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.competition.messaging.CompetitionReportRequest;
import com.stock_spoon.river_be.competition.messaging.CompetitionReportRequestPublisher;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitionReportRequestPublisherTests {
    private static final String URL = "https://sqs.ap-northeast-2.amazonaws.com/250832562715/stockspoon-v2-dev-report-request";
    private static final String EVENT = "550e8400-e29b-41d4-a716-446655440001";
    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final CompetitionReportRequestPublisher publisher = new CompetitionReportRequestPublisher(sqs, json, URL);

    @Test void sendsConfirmedBodyAndStableEventAttributeToStandardQueue() {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                SendMessageResponse.builder().messageId("sqs-message-1").build()));
        var payload = payload();
        assertThat(publisher.publish(EVENT, payload)).isEqualTo("sqs-message-1");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        var request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(URL);
        assertThat(json.readTree(request.messageBody())).isEqualTo(json.readTree("""
                {"competition_id":123,"participant_id":456,
                 "portfolio_reason_ids":["550e8400-e29b-41d4-a716-446655440000"]}
                """));
        assertThat(request.messageAttributes().get("eventId").stringValue()).isEqualTo(EVENT);
        assertThat(request.messageGroupId()).isNull();
        assertThat(request.messageDeduplicationId()).isNull();
    }

    @Test void retryUsesSameEventId() {
        var failure = new IllegalStateException("SQS unavailable");
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(failure))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("retry-success").build()));
        assertThatThrownBy(() -> publisher.publish(EVENT, payload())).hasCause(failure);
        assertThat(publisher.publish(EVENT, payload())).isEqualTo("retry-success");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r ->
                assertThat(r.messageAttributes().get("eventId").stringValue()).isEqualTo(EVENT));
    }

    @Test void responseWithoutMessageIdIsNotTreatedAsSuccess() {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                SendMessageResponse.builder().build()));
        assertThatThrownBy(() -> publisher.publish(EVENT, payload())).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsInvalidIdentityAndReasonIdsBeforeSending() {
        assertThatThrownBy(() -> new CompetitionReportRequest(0, 456, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompetitionReportRequest(123, 456, List.of("invalid"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish("", payload())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sqs);
    }

    private CompetitionReportRequest payload() {
        return new CompetitionReportRequest(123, 456, List.of("550e8400-e29b-41d4-a716-446655440000"));
    }
}
