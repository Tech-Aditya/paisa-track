package com.paisatrack.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI paisaTrackOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PaisaTrack API")
                        .version("v1")
                        .description("""
                                Personal finance, daily expense and money management API.
                                All responses use the envelope {success, message, data, error, requestId, timestamp}.
                                Authenticate with POST /api/v1/auth/login and send the access token as
                                'Authorization: Bearer <token>'."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
