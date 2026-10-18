package com.stock_spoon.river_be.dbwrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DbWriteReplayServiceTests {
    private static final String QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/db-write-replay.fifo";

    @Mock
    private SqsClient sqs;

    @Test
    void enqueuesAReplayableCommandToOneFifoMessageGroup() throws Exception {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
                SendMessageResponse.builder().messageId("sqs-message-1").build());
        var service = new DbWriteReplayService(sqs, true, QUEUE_URL, "");
        var request = new OrderCreateRequest("005930", "buy", "limit", 70000L, 2L,
                "migration rehearsal", "Samsung", List.of(), 30.0, false);

        var requestId = service.enqueueIfWriterUnavailable(
                new SQLException("read_only", "HY000", 1290),
                DbWriteReplayService.Operation.ORDER_CREATE,
                new DbWriteReplayService.OrderCreatePayload(42, 7, Order.Source.USER, request));

        assertThat(requestId).isPresent();
        var sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(sent.getValue().messageGroupId()).isEqualTo("db-write-replay-global");
        assertThat(sent.getValue().messageDeduplicationId()).isEqualTo(requestId.get());

        var body = JsonMapper.builder().build().readTree(sent.getValue().messageBody());
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.path("requestId").asText()).isEqualTo(requestId.get());
        assertThat(body.path("operation").asText()).isEqualTo("ORDER_CREATE");
        assertThat(body.path("payload").path("userId").asLong()).isEqualTo(42);
        assertThat(body.path("payload").path("accountId").asLong()).isEqualTo(7);
        assertThat(body.path("payload").path("request").path("stock_code").asText()).isEqualTo("005930");
    }

    @Test
    void leavesTheDatabaseErrorUnqueuedWhenFeatureIsDisabled() {
        var service = new DbWriteReplayService(sqs, false, "", "");

        var requestId = service.enqueueIfWriterUnavailable(
                new SQLException("read_only", "HY000", 1290),
                DbWriteReplayService.Operation.ORDER_CANCEL,
                new DbWriteReplayService.OrderCancelPayload(42, 7, 3));

        assertThat(requestId).isEmpty();
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }
}
