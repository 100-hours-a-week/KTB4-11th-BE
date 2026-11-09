package com.stock_spoon.river_be.order.messaging;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class OrderRequestPublisher {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderRequestPublisher.class);
    private final SqsAsyncClient sqs;
    private final OrderRequestCodec codec;
    private final String url;
    public OrderRequestPublisher(SqsAsyncClient sqs,OrderRequestCodec codec,String url) { this.sqs=sqs; this.codec=codec; this.url=url; }
    public String publish(OrderRequestMessage m) {
        var request=SendMessageRequest.builder().queueUrl(url).messageBody(codec.encode(m))
                .messageGroupId(Long.toString(m.accountId())).messageDeduplicationId(m.eventId()).build();
        try { return sqs.sendMessage(request).get(10,TimeUnit.SECONDS).messageId(); }
        catch(InterruptedException e) {
            log.warn("event=order_queue_send_failed stage=sqs_send code=SEND_INTERRUPTED eventId={} correlationId={} accountId={}",
                    m.eventId(),m.correlationId(),m.accountId());
            Thread.currentThread().interrupt(); throw new IllegalStateException("Order publication interrupted",e);
        }
        catch(java.util.concurrent.ExecutionException e) {
            log.warn("event=order_queue_send_failed stage=sqs_send code=SEND_EXECUTION_FAILED eventId={} correlationId={} accountId={} causeType={}",
                    m.eventId(),m.correlationId(),m.accountId(),e.getCause()==null?e.getClass().getSimpleName():e.getCause().getClass().getSimpleName());
            throw new IllegalStateException("Order publication failed",e.getCause());
        }
        catch(java.util.concurrent.TimeoutException e) {
            log.warn("event=order_queue_send_failed stage=sqs_send code=SEND_OUTCOME_UNKNOWN eventId={} correlationId={} accountId={}",
                    m.eventId(),m.correlationId(),m.accountId());
            throw new IllegalStateException("Order publication outcome unknown",e);
        }
    }
}
