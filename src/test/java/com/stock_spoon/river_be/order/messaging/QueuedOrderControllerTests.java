package com.stock_spoon.river_be.order.messaging;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"order.queue.enabled=true", "order.queue.consumer-enabled=false",
        "order.queue.url=https://sqs.ap-northeast-2.amazonaws.com/123/test.fifo", "order.queue.reconcile-delay-ms=3600000"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QueuedOrderControllerTests {
    @TestConfiguration static class Reservations {
        @Bean @Primary OrderReservationStore memoryReservations() { return new OrderRequestTransactionsTests.MemoryReservations(); }
    }
    @MockitoBean(name="orderSqsClient") SqsAsyncClient sqs;
    @MockitoBean OrderRequestPublisher publisher;
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired OrderRepository orders;
    MockMvc mvc; Account account;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        account=accounts.save(new Account(users.save(new User("queue-http")),"manual",100000,false));
        when(publisher.publish(any())).thenReturn("sqs-id");
    }
    @Test void acceptsAfterRedisReservationAndReturnsIdWithoutCreatingDbOrder() throws Exception {
        String id=UUID.randomUUID().toString();
        submit(id,70000).andExpect(status().isAccepted()).andExpect(jsonPath("request_id").value(id));
        assertThat(orders.findAll().stream().filter(o->o.getAccountId().equals(account.getId()))).isEmpty();
        submit(UUID.randomUUID().toString(),70000).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/{id}/order-requests/{event}",account.getId(),id)
                .with(jwt().jwt(j->j.subject(account.getUser().getId().toString()).claim("actor","USER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("processing"));
    }
    @Test void missingIdempotencyKeyFailsBeforePublishing() throws Exception { submit(null,70000).andExpect(status().isBadRequest()); }
    org.springframework.test.web.servlet.ResultActions submit(String id,long price) throws Exception {
        var request=post("/api/v1/accounts/{id}/orders",account.getId()).with(csrf())
                .with(jwt().jwt(j->j.subject(account.getUser().getId().toString()).claim("actor","USER")))
                .contentType("application/json").content("{\"stock_code\":\"005930\",\"order_side\":\"buy\",\"order_type\":\"limit\",\"limit_price\":"+price+",\"quantity\":1}");
        if(id!=null) request.header("Idempotency-Key",id);
        return mvc.perform(request);
    }
}
