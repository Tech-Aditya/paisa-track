package com.paisatrack.security.ratelimit;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param auth limit per client IP and endpoint on POST /api/v1/auth/** */
@Validated
@ConfigurationProperties("paisatrack.security.rate-limit")
public record RateLimitProperties(boolean enabled, @Valid @NotNull Policy auth) {

    public record Policy(@Min(1) int maxRequests, @NotNull Duration window) {
    }
}
