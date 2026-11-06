package com.stock_spoon.river_be.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI stockSpoonOpenApi() {
        return new OpenAPI().info(new Info().title("Stock Spoon API").version("1.0")
                .description("Stock Spoon 백엔드 API. 같은 서버에서 로그인한 브라우저의 HttpOnly 쿠키로 인증합니다. "
                        + "Swagger는 변경 요청 전에 CSRF 토큰을 발급받아 헤더로 전달합니다. "
                        + "Authorize 입력창으로 브라우저 쿠키를 설정할 수 없으며 실제 로그인이 필요합니다."))
                .components(new Components()
                        .addSecuritySchemes("accessCookie", cookie("access_token", "로그인 응답으로 설정되는 HttpOnly 쿠키"))
                        .addSecuritySchemes("refreshCookie", cookie("refresh_token", "재발급·로그아웃에 사용하는 HttpOnly 쿠키")))
                .addSecurityItem(new SecurityRequirement().addList("accessCookie"));
    }

    private SecurityScheme cookie(String name, String description) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
                .name(name).description(description);
    }

    @Bean
    OperationCustomizer authOperationSecurity() {
        return (operation, handler) -> {
            boolean authEndpoint = handler.getBeanType() == com.stock_spoon.river_be.auth.controller.AuthController.class;
            if (!authEndpoint || java.util.Set.of("reissue", "logout").contains(handler.getMethod().getName())) {
                operation.getResponses().putIfAbsent("401", errorResponse("로그인 정보가 없거나 유효하지 않습니다.", "UNAUTHORIZED"));
            }
            var mapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), org.springframework.web.bind.annotation.RequestMapping.class);
            if (!authEndpoint || (mapping != null && java.util.Arrays.stream(mapping.method())
                    .anyMatch(method -> method == org.springframework.web.bind.annotation.RequestMethod.POST))) {
                operation.getResponses().putIfAbsent("403", errorResponse(
                        "권한이 없거나 CSRF 쿠키·헤더 확인에 실패했습니다. 변경 요청은 CSRF 토큰이 필요합니다.", "INVALID_CSRF_TOKEN"));
            }
            if (authEndpoint) {
                operation.setSecurity(new java.util.ArrayList<>());
                if (java.util.Set.of("reissue", "logout").contains(handler.getMethod().getName())) {
                    operation.addSecurityItem(new SecurityRequirement().addList("refreshCookie"));
                }
            }
            return operation;
        };
    }

    private io.swagger.v3.oas.models.responses.ApiResponse errorResponse(String description, String code) {
        var schema = new io.swagger.v3.oas.models.media.ObjectSchema()
                .addProperty("code", new io.swagger.v3.oas.models.media.StringSchema().example(code))
                .addProperty("message", new io.swagger.v3.oas.models.media.StringSchema().example(description));
        return new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                        new io.swagger.v3.oas.models.media.MediaType().schema(schema)));
    }
}
