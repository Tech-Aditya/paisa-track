package com.paisatrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.paisatrack.support.IntegrationTest;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registerCreatesAccountWithDefaultPreferencesAndReturnsTokens() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(postJson("/api/v1/auth/register",
                        Map.of("email", email, "password", PASSWORD, "fullName", "  Asha Rao ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId", not(blankOrNullString())))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.refreshToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.data.user.preferences.defaultCurrency").value("INR"))
                .andExpect(jsonPath("$.data.user.preferences.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.user.preferences.weekStartDay").value("MONDAY"))
                .andExpect(jsonPath("$.data.user.preferences.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
    }

    @Test
    void registerAcceptsCurrencyAndTimezone() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "email", uniqueEmail(), "password", PASSWORD, "fullName", "Sam",
                        "defaultCurrency", "USD", "timezone", "America/New_York")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.preferences.defaultCurrency").value("USD"))
                .andExpect(jsonPath("$.data.user.preferences.timezone").value("America/New_York"));
    }

    @Test
    void passwordIsStoredHashedNeverPlain() throws Exception {
        String email = uniqueEmail();
        register(email);

        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/v1/auth/register",
                        Map.of("email", email.toUpperCase(), "password", PASSWORD, "fullName", "Dup")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void registerValidatesEveryField() throws Exception {
        mockMvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "email", "not-an-email", "password", "short", "fullName", " ",
                        "defaultCurrency", "RUPEES", "timezone", "Mars/Base")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'fullName')]").exists())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'defaultCurrency')]").exists())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'timezone')]").exists());
    }

    @Test
    void malformedJsonGivesCleanError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{\"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));
    }

    @Test
    void loginSucceedsWithAnyEmailCase() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", "  " + email.toUpperCase() + " ", "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.user.lastLoginAt", notNullValue()));
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email);

        String wrongPassword = mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", "Wrong1234")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", uniqueEmail(), "password", "Wrong1234")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.message"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.message"));
    }

    @Test
    void refreshRotatesTokensAndOldRefreshTokenStopsWorking() throws Exception {
        Session session = register(uniqueEmail());

        String body = mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(session.userId().toString()))
                .andReturn().getResponse().getContentAsString();
        String newRefresh = JsonPath.read(body, "$.data.refreshToken");
        String newAccess = JsonPath.read(body, "$.data.accessToken");
        assertThat(newRefresh).isNotEqualTo(session.refreshToken());

        mockMvc.perform(getAuthed("/api/v1/users/me", newAccess)).andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void reusingRotatedRefreshTokenRevokesTheWholeFamily() throws Exception {
        Session session = register(uniqueEmail());
        String rotated = refresh(session.refreshToken());

        // Attacker replays the old token: rejected, and the legitimate new token is revoked too.
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", rotated)))
                .andExpect(status().isUnauthorized());

        Integer active = jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, session.userId());
        assertThat(active).isZero();
    }

    @Test
    void reuseDetectionDoesNotAffectOtherSessionsOfTheSameUser() throws Exception {
        String email = uniqueEmail();
        Session phone = register(email);
        Session laptop = login(email, PASSWORD);

        refresh(phone.refreshToken());
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", phone.refreshToken())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", laptop.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentRefreshWithSameTokenSucceedsAtMostOnce() throws Exception {
        Session session = register(uniqueEmail());
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSizeLessThanOrEqualTo(1);
            assertThat(statuses).allMatch(s -> s == 200 || s == 401);
        } finally {
            pool.shutdownNow();
        }

        // The original token was exchanged at most once: no duplicate successor tokens exist.
        Integer tokens = jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ?", Integer.class, session.userId());
        assertThat(tokens).isLessThanOrEqualTo(2);
    }

    @Test
    void logoutRevokesTheSessionAndIsIdempotent() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", "never-issued")))
                .andExpect(status().isNoContent());

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesEverySessionOfTheCaller() throws Exception {
        String email = uniqueEmail();
        Session first = register(email);
        Session second = login(email, PASSWORD);

        mockMvc.perform(authed(post("/api/v1/auth/logout-all"), first.accessToken()))
                .andExpect(status().isNoContent());

        for (Session s : List.of(first, second)) {
            mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", s.refreshToken())))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", is("UNAUTHENTICATED")));
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        Session session = register(uniqueEmail());

        Integer plain = jdbc.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Integer.class, session.refreshToken());
        Integer hashed = jdbc.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                RefreshTokenService.hash(session.refreshToken()));
        assertThat(plain).isZero();
        assertThat(hashed).isOne();
    }

    private String refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.refreshToken");
    }
}
