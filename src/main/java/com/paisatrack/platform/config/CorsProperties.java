package com.paisatrack.platform.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins allowed to call the API. Empty = no cross-origin access. */
@ConfigurationProperties("paisatrack.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
