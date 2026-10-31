package com.paisatrack.modules.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.paisatrack.support.IntegrationTest;

/** Users see and change only their own data; client-supplied ids are ignored. */
class UserIsolationIntegrationTest extends IntegrationTest {

    @Test
    void eachUserSeesOnlyTheirOwnProfile() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        Session a = register(emailA);
        Session b = register(emailB);

        mockMvc.perform(getAuthed("/api/v1/users/me", a.accessToken()))
                .andExpect(jsonPath("$.data.id").value(a.userId().toString()))
                .andExpect(jsonPath("$.data.email").value(emailA));
        mockMvc.perform(getAuthed("/api/v1/users/me", b.accessToken()))
                .andExpect(jsonPath("$.data.id").value(b.userId().toString()))
                .andExpect(jsonPath("$.data.email").value(emailB));
    }

    @Test
    void updatesByOneUserNeverTouchAnother() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("defaultCurrency", "GBP"))))
                .andExpect(status().isOk());
        mockMvc.perform(authed(patch("/api/v1/users/me"), a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("fullName", "Only A"))))
                .andExpect(status().isOk());

        mockMvc.perform(getAuthed("/api/v1/users/me", b.accessToken()))
                .andExpect(jsonPath("$.data.fullName").value("Test User"))
                .andExpect(jsonPath("$.data.preferences.defaultCurrency").value("INR"));
    }

    @Test
    void clientSuppliedUserIdsAreIgnored() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());

        // Extra "id"/"userId" fields in the body must not redirect the update to user B.
        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), a.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", b.userId().toString(), "id", b.userId().toString(),
                                "defaultCurrency", "JPY"))))
                .andExpect(status().isOk());

        mockMvc.perform(getAuthed("/api/v1/users/me/preferences", b.accessToken()))
                .andExpect(jsonPath("$.data.defaultCurrency").value("INR"));
        mockMvc.perform(getAuthed("/api/v1/users/me/preferences", a.accessToken()))
                .andExpect(jsonPath("$.data.defaultCurrency").value("JPY"));
    }

    @Test
    void usersCannotBeAddressedById() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());

        mockMvc.perform(getAuthed("/api/v1/users/" + b.userId(), a.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/users/" + b.userId() + "/preferences", a.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutAllOnlyAffectsTheCaller() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());

        mockMvc.perform(authed(post("/api/v1/auth/logout-all"), a.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", b.refreshToken())))
                .andExpect(status().isOk());
    }
}
