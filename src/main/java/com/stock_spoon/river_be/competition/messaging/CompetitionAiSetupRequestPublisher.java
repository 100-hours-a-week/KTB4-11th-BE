package com.stock_spoon.river_be.competition.messaging;

import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class CompetitionAiSetupRequestPublisher {
    private final SqsAsyncClient sqs;
    private final String queueUrl;
    public CompetitionAiSetupRequestPublisher(SqsAsyncClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }
    public void publish(String payload) {
        if (payload == null || payload.isBlank() || payload.getBytes(StandardCharsets.UTF_8).length > 1048576) {
            throw new IllegalArgumentException("Invalid AI setup payload");
        }
        try {
            var response = sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                    .messageBody(payload).build()).get(12, TimeUnit.SECONDS);
            if (response == null || response.messageId() == null || response.messageId().isBlank()) {
                throw new IllegalStateException("Missing SQS message ID");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI setup publish interrupted", error);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            throw new IllegalStateException("AI setup publish failed", error);
        }
    }
}
