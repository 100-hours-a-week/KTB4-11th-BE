package com.stock_spoon.river_be.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Uses the masked token returned by the API because the CSRF cookie is HttpOnly. */
@Component
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", matchIfMissing = true)
public class SwaggerCsrfTransformer extends SwaggerIndexPageTransformer {
    public SwaggerCsrfTransformer(SwaggerUiConfigProperties config, SwaggerUiOAuthProperties oauth,
            SwaggerWelcomeCommon welcome, ObjectMapperProvider mapper) {
        super(config, oauth, welcome, mapper);
    }

    @Override
    protected String addCSRF(String html) {
        try {
            String interceptor = new ClassPathResource("swagger/csrf-interceptor.js")
                    .getContentAsString(StandardCharsets.UTF_8);
            return html.replace("window.ui = SwaggerUIBundle({",
                    "window.ui = SwaggerUIBundle({\nrequestInterceptor: " + interceptor + ",\n");
        } catch (IOException error) {
            throw new UncheckedIOException("Swagger CSRF interceptor could not be loaded", error);
        }
    }
}
