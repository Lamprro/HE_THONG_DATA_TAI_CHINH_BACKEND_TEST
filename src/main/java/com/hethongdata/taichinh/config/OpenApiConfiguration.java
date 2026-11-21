package com.hethongdata.taichinh.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin")
                .pathsToMatch("/api/**").pathsToExclude("/api/v1/**", "/api/health/**")
                .addOpenApiCustomizer(api -> api.addSecurityItem(new SecurityRequirement().addList("adminBearer")))
                .build();
    }

    @Bean
    OpenAPI financialApi() {
        return new OpenAPI()
                .info(new Info().title("Financial Data API").version("1"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("adminBearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")));
    }
}
