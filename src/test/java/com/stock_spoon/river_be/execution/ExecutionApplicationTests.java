package com.stock_spoon.river_be.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ExecutionApplication.class, properties = {
        "execution.secret=execution-test-key", "kiwoom.stream.enabled=false"})
@ActiveProfiles("execution")
class ExecutionApplicationTests {
    @Autowired ApplicationContext context;
    @Autowired WebApplicationContext web;
    @Test
    void excludesBeApisAndOAuthButLoadsExecutionWorkers() {
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.order.controller.OrderController.class)).isEmpty();
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.config.SecurityConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(com.stock_spoon.river_be.execution.listener.OrderExecutionListener.class)).hasSize(1);
    }
    @Test
    void internalKeyRequiredAndPublicApiDenied() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(post("/internal/execution/initial").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/execution/initial").header("X-Execution-Key", "wrong").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/execution/initial").header("X-Execution-Key", "execution-test-key", "execution-test-key")
                .contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").header("X-Execution-Key", "execution-test-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/execution/initial").header("X-Execution-Key", "execution-test-key")
                .contentType("application/json").content("{}")) .andExpect(status().isBadRequest());
    }
}
