package com.paisatrack.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.paisatrack.support.IntegrationTest;

class UserProfileIntegrationTest extends IntegrationTest {

    @Test
    void getMyProfile() throws Exception {
        String email = uniqueEmail();
        Session session = register(email);

        mockMvc.perform(getAuthed("/api/v1/users/me", session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(session.userId().toString()))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.preferences.defaultCurrency").value("INR"));
    }

    @Test
    void patchProfileChangesOnlySuppliedFields() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(authed(patch("/api/v1/users/me"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("fullName", "Priya Sharma"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Priya Sharma"));

        mockMvc.perform(authed(patch("/api/v1/users/me"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Priya Sharma"));

        mockMvc.perform(authed(patch("/api/v1/users/me"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("fullName", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("fullName"));
    }

    @Test
    void updatePreferences() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultCurrency", "EUR", "weekStartDay", "SUNDAY", "theme", "DARK"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultCurrency").value("EUR"))
                .andExpect(jsonPath("$.data.weekStartDay").value("SUNDAY"))
                .andExpect(jsonPath("$.data.theme").value("DARK"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Kolkata"));

        mockMvc.perform(getAuthed("/api/v1/users/me/preferences", session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultCurrency").value("EUR"))
                .andExpect(jsonPath("$.data.weekStartDay").value("SUNDAY"));
    }

    @Test
    void invalidPreferencesAreRejected() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultCurrency", "inr", "timezone", "+05:30"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'defaultCurrency')]").exists())
                .andExpect(jsonPath("$.error.fields[?(@.field == 'timezone')]").exists());

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("defaultCurrency", "XXX"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("weekStartDay", "FUNDAY"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void changePasswordSignsOutEverySessionAndOnlyNewPasswordWorks() throws Exception {
        String email = uniqueEmail();
        Session first = register(email);
        Session second = login(email, PASSWORD);

        mockMvc.perform(authed(put("/api/v1/users/me/password"), first.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "BetterPass456"))))
                .andExpect(status().isNoContent());

        for (Session s : new Session[] {first, second}) {
            mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", s.refreshToken())))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isUnauthorized());
        login(email, "BetterPass456");
    }

    @Test
    void changePasswordRequiresCorrectCurrentPassword() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(authed(put("/api/v1/users/me/password"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", "Wrong1234", "newPassword", "BetterPass456"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_INCORRECT"));

        // Sessions survive a failed attempt.
        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordRejectsSamePasswordAndWeakPassword() throws Exception {
        Session session = register(uniqueEmail());

        mockMvc.perform(authed(put("/api/v1/users/me/password"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));

        mockMvc.perform(authed(put("/api/v1/users/me/password"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", "lettersonly"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("newPassword"));
    }
}
