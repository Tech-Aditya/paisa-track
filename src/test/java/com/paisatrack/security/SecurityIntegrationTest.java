package com.paisatrack.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.paisatrack.support.IntegrationTest;

class SecurityIntegrationTest extends IntegrationTest {

    @Autowired
    JwtEncoder applicationEncoder;

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void unknownApiPathsAreNotRevealedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUnknownPathReturnsEnvelopedNotFound() throws Exception {
        Session session = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/does-not-exist", session.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(getAuthed("/api/v1/users/me", "not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        Session session = register(uniqueEmail());
        byte[] otherKey = new byte[64];
        new SecureRandom().nextBytes(otherKey);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));

        String forged = encode(forger, claims(session.userId(), jwtProperties.audience(),
                Instant.now(), Instant.now().plusSeconds(600)));

        mockMvc.perform(getAuthed("/api/v1/users/me", forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedTokenIsRejected() throws Exception {
        Session session = register(uniqueEmail());
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + session.userId() + "\",\"iss\":\"paisatrack\","
                + "\"aud\":\"paisatrack-api\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}")
                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(getAuthed("/api/v1/users/me", header + "." + payload + "."))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Session session = register(uniqueEmail());
        Instant past = Instant.now().minusSeconds(3600);
        String expired = encode(applicationEncoder, claims(session.userId(), jwtProperties.audience(),
                past.minusSeconds(900), past));

        mockMvc.perform(getAuthed("/api/v1/users/me", expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        Session session = register(uniqueEmail());
        String wrongAudience = encode(applicationEncoder, claims(session.userId(), "some-other-api",
                Instant.now(), Instant.now().plusSeconds(600)));

        mockMvc.perform(getAuthed("/api/v1/users/me", wrongAudience)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        Session session = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/users/me", session.refreshToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void requestIdIsGeneratedOrEchoedWhenSafe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "client-trace-1234"))
                .andExpect(header().string("X-Request-Id", "client-trace-1234"));
        mockMvc.perform(get("/actuator/health").header("X-Request-Id", "bad id\r\ninjected"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f]{16}")));
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/me']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    private static JwtClaimsSet claims(UUID subject, String audience, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer("paisatrack")
                .audience(List.of(audience))
                .subject(subject.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
