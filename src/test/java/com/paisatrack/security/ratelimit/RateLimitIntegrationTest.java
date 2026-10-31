package com.paisatrack.security.ratelimit;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.paisatrack.support.IntegrationTest;

@TestPropertySource(properties = {
        "paisatrack.security.rate-limit.auth.max-requests=3",
        "paisatrack.security.rate-limit.auth.window=1h"
})
class RateLimitIntegrationTest extends IntegrationTest {

    @Test
    void loginIsThrottledPerClientAfterTheLimit() throws Exception {
        Map<String, String> body = Map.of("email", uniqueEmail(), "password", "Wrong1234");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(postJson("/api/v1/auth/login", body)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(postJson("/api/v1/auth/login", body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", notNullValue()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
