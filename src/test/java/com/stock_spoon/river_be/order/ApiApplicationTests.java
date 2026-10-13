package com.stock_spoon.river_be.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = com.stock_spoon.river_be.RiverBeApplication.class,
        properties = {"execution.url=http://localhost:9999", "execution.secret=api-test-key"})
@ActiveProfiles("api")
class ApiApplicationTests {
    @Autowired ApplicationContext context;
    @Test
    void beHasRemoteGatewayButNoExecutionListenerOrService() {
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.execution.listener.OrderExecutionListener.class)).isEmpty();
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.execution.service.OrderExecutionService.class)).isEmpty();
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.order.client.RemoteExecutionGateway.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.order.controller.OrderController.class)).hasSize(1);
    }
}
