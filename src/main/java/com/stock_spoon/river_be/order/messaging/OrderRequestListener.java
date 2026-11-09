package com.stock_spoon.river_be.order.messaging;

import io.awspring.cloud.sqs.annotation.SqsListener;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.model.Message;

public class OrderRequestListener {
    private final OrderRequestCodec codec;
    private final OrderRequestService service;
    public OrderRequestListener(OrderRequestCodec codec,OrderRequestService service) { this.codec=codec; this.service=service; }
    @SqsListener(value="${order.queue.url}",factory="orderSqsListenerContainerFactory", acknowledgementMode="ON_SUCCESS")
    public void onMessage(Message message) {
        var sent=message.attributesAsStrings().get("SentTimestamp");
        if(sent==null) {
            LoggerFactory.getLogger(getClass()).warn("event=order_queue_message_rejected stage=message_attributes code=SENT_TIMESTAMP_MISSING messageId={}",message.messageId());
            throw new IllegalArgumentException("Missing SQS SentTimestamp");
        }
        var request=codec.decode(message.body(),Instant.ofEpochMilli(Long.parseLong(sent)));
        LoggerFactory.getLogger(getClass()).info("event=order_request_received messageId={} correlationId={} eventId={}",
                message.messageId(),request.correlationId(),request.eventId());
        service.consume(request);
    }
    @Scheduled(fixedDelayString="${order.queue.reconcile-delay-ms:30000}")
    public void reconcile() { service.reconcile(); }
}
