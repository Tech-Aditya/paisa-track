package com.paisatrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class JwtConfigTest {

    @Test
    void missingSecretFailsUnlessEphemeralKeysAreAllowed() {
        assertThatThrownBy(() -> JwtConfig.resolveSigningKey(props(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not set");

        assertThat(JwtConfig.resolveSigningKey(props("", true)).getEncoded()).hasSize(64);
    }

    @Test
    void secretMustBeBase64AndLongEnough() {
        assertThatThrownBy(() -> JwtConfig.resolveSigningKey(props("not base64 !!", false)))
                .hasMessageContaining("base64");
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> JwtConfig.resolveSigningKey(props(tooShort, false)))
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void validSecretIsUsedAsIs() {
        byte[] key = new byte[48];
        key[0] = 7;
        assertThat(JwtConfig.resolveSigningKey(props(Base64.getEncoder().encodeToString(key), false)).getEncoded())
                .isEqualTo(key);
    }

    @Test
    void secretIsNotPrinted() {
        assertThat(props("c2VjcmV0", false).toString()).doesNotContain("c2VjcmV0");
    }

    private static JwtProperties props(String secret, boolean allowEphemeral) {
        return new JwtProperties("paisatrack", "paisatrack-api", secret, allowEphemeral,
                Duration.ofMinutes(15), Duration.ofDays(30));
    }
}
