package com.stock_spoon.river_be.competition.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;

public class CompetitionReportRequestPublisher {
    private static final Logger log = LoggerFactory.getLogger(CompetitionReportRequestPublisher.class);
    private final SqsAsyncClient sqs;
    private final JsonMapper json;
    private final String queueUrl;

    public CompetitionReportRequestPublisher(SqsAsyncClient sqs, JsonMapper json, String queueUrl) {
        this.sqs = sqs;
        this.json = json;
        this.queueUrl = queueUrl;
    }

    // requestId는 Outbox의 값을 그대로 받는다. 재시도할 때 새 ID를 만들지 않는다.
    public String publish(String requestId, CompetitionReportRequest payload) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128 || payload == null) {
            throw new IllegalArgumentException("리포트 요청 식별자와 본문을 확인하세요.");
        }
        String stage = "encode";
        try {
            String body = json.writeValueAsString(payload);
            if (body.getBytes(StandardCharsets.UTF_8).length > 1048576) {
                throw new IllegalArgumentException("리포트 요청이 SQS 메시지 크기 제한을 초과합니다.");
            }
            // Standard Queue에는 FIFO 전용 group/deduplication ID를 설정하지 않는다.
            var request = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body)
                    .messageAttributes(java.util.Map.of("eventId", MessageAttributeValue.builder()
                            .dataType("String").stringValue(requestId).build())).build();
            stage = "sqs_send";
            log.info("event=competition_report_publish_started competitionId={} participantId={} requestId={} reasonCount={}",
                    payload.getCompetitionId(), payload.getParticipantId(), requestId, payload.getPortfolioReasonIds().size());
            var response = sqs.sendMessage(request).get(12, TimeUnit.SECONDS);
            if (response == null || response.messageId() == null || response.messageId().isBlank()) {
                throw new IllegalStateException("SQS 전송 결과의 messageId를 확인할 수 없습니다.");
            }
            log.info("event=competition_report_published competitionId={} participantId={} requestId={} messageId={}",
                    payload.getCompetitionId(), payload.getParticipantId(), requestId, response.messageId());
            return response.messageId();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure(stage, requestId, payload, error);
        } catch (ExecutionException error) {
            throw failure(stage, requestId, payload, error.getCause());
        } catch (TimeoutException | RuntimeException error) {
            throw failure(stage, requestId, payload, error);
        }
    }

    private IllegalStateException failure(String stage, String requestId, CompetitionReportRequest payload, Throwable error) {
        // 타임아웃·연결 단절은 실제 전송 여부가 불명확할 수 있다. SENT 저장 여부는 호출자가 성공 반환으로 판단한다.
        log.error("event=competition_report_publish_failed stage={} competitionId={} participantId={} requestId={} causeType={}",
                stage, payload.getCompetitionId(), payload.getParticipantId(), requestId, error.getClass().getSimpleName(), error);
        return new IllegalStateException("대결 리포트 요청 전송 결과를 확인할 수 없습니다.", error);
    }
}
