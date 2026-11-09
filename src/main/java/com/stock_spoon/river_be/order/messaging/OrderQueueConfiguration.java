package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.execution.listener.OrderExecutionListener;
import com.stock_spoon.river_be.execution.service.*;
import com.stock_spoon.river_be.market.MarketStream;
import com.stock_spoon.river_be.order.repository.*;
import com.stock_spoon.river_be.order.reservation.*;
import com.stock_spoon.river_be.order.service.*;
import com.stock_spoon.river_be.order.validator.OrderMarketValidator;
import io.awspring.cloud.sqs.config.SqsBootstrapConfiguration;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="order.queue.enabled",havingValue="true")
@Import(SqsBootstrapConfiguration.class)
public class OrderQueueConfiguration {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderQueueConfiguration.class);
    @Bean(destroyMethod="close")
    SqsAsyncClient orderSqsClient(@Value("${order.queue.region:ap-northeast-2}") String region,
            @Value("${order.queue.url}") String url) {
        if(!url.startsWith("https://sqs."+region+".amazonaws.com/") || !url.endsWith(".fifo")) {
            log.error("event=order_queue_configuration_failed stage=sqs_client code=REGIONAL_FIFO_QUEUE_URL_REQUIRED");
            throw new IllegalStateException("ORDER_QUEUE_URL must be a regional SQS FIFO queue URL");
        }
        return SqsAsyncClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(45)).apiCallAttemptTimeout(Duration.ofSeconds(35))).build();
    }
    @Bean
    SqsMessageListenerContainerFactory<Object> orderSqsListenerContainerFactory(@Qualifier("orderSqsClient") SqsAsyncClient sqs,
            @Value("${order.queue.consumer-enabled:true}") boolean start,
            @Value("${order.queue.max-concurrent-messages:10}") int concurrency) {
        return SqsMessageListenerContainerFactory.builder().sqsAsyncClient(sqs).configure(o->o
                .autoStartup(start).pollTimeout(Duration.ofSeconds(20))
                .maxConcurrentMessages(concurrency).maxMessagesPerPoll(Math.min(concurrency,10))
                .messageVisibility(Duration.ofSeconds(120)).queueNotFoundStrategy(QueueNotFoundStrategy.FAIL)
                .acknowledgementMode(AcknowledgementMode.ON_SUCCESS)
                .acknowledgementInterval(Duration.ZERO).acknowledgementThreshold(0)).build();
    }
    @Bean OrderRequestCodec orderRequestCodec(JsonMapper json) { return new OrderRequestCodec(json); }
    @Bean OrderReservationStore orderReservationStore(StringRedisTemplate redis,JsonMapper json) { return new RedisOrderReservationStore(redis,json); }
    @Bean OrderRequestValidator orderRequestValidator(Validator validator,OrderMarketValidator prices) { return new OrderRequestValidator(validator,prices); }
    @Bean OrderRequestTransactions orderRequestTransactions(AccountRepository accounts,OrderRequestRecordRepository records,EntityManager em,
            PlatformTransactionManager manager,OrderService orders,OrderExecutionService execution,OrderRequestValidator validator,
            OrderReservationStore store,MarketOrderPricingService pricing) {
        return new OrderRequestTransactions(accounts,records,em,manager,orders,execution,validator,store,pricing,Clock.systemUTC());
    }
    @Bean OrderRequestPublisher orderRequestPublisher(@Qualifier("orderSqsClient") SqsAsyncClient sqs,OrderRequestCodec codec,
            @Value("${order.queue.url}") String url) { return new OrderRequestPublisher(sqs,codec,url); }
    @Bean OrderRequestService orderRequestService(OrderRequestTransactions tx,OrderReservationStore store,OrderRequestPublisher publisher,
            OrderSubscriptionService subscriptions,MarketStream stream,OrderBuyPriceService prices,OrderRepository orders,OrderExecutionListener execution) {
        return new OrderRequestService(tx,store,publisher,subscriptions,stream,prices,orders,execution,Clock.systemUTC());
    }
    @Bean OrderRequestListener orderRequestListener(OrderRequestCodec codec,OrderRequestService service) { return new OrderRequestListener(codec,service); }
}
