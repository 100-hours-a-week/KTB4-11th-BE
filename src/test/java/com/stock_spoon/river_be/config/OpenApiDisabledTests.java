package com.stock_spoon.river_be.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.api-docs.path=/api/v3/api-docs", "springdoc.swagger-ui.path=/api/swagger-ui.html", "springdoc.swagger-ui.enabled=false"})
class OpenApiDisabledTests {
    @Autowired WebApplicationContext context;

    @Test void documentationCanBeDisabled() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/api/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
