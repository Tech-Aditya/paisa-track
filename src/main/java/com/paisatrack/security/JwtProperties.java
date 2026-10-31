package com.paisatrack.security;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret               base64 HMAC key, at least 32 bytes (JWT_SECRET)
 * @param allowEphemeralSecret local/test only: use a random key when no secret is set
 */
@Validated
@ConfigurationProperties("paisatrack.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        String secret,
        boolean allowEphemeralSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", secret=***]";
    }
}
