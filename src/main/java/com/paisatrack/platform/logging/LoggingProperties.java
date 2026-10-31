package com.paisatrack.platform.logging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param slowRequestThreshold requests taking at least this long are logged at WARN */
@ConfigurationProperties("paisatrack.logging")
public record LoggingProperties(Duration slowRequestThreshold) {

    public LoggingProperties {
        slowRequestThreshold = slowRequestThreshold != null ? slowRequestThreshold : Duration.ofSeconds(1);
    }
}
