package com.paisatrack.modules.transaction;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class CategorySuggestionIntegrationTest extends FinanceIntegrationTest {

    private String url(String text, String type) {
        return "/api/v1/transactions/suggest-category?type=" + type + "&text=" + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void yourOwnHistoryComesBeforeKnownMerchants() throws Exception {
        Session s = register(uniqueEmail());
        UUID dinner = category(s, "FOOD_DINNER");
        expense(s, "350", dinner, "merchant", "Ramu Dhaba");
        expense(s, "420", dinner, "merchant", "Ramu Dhaba", "description", "family dinner");
        // You file Swiggy under dinner; that beats the built-in "restaurants" rule.
        expense(s, "500", dinner, "merchant", "Swiggy");

        mockMvc.perform(getAuthed(url("ramu dhaba", "EXPENSE"), s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryId").value(dinner.toString()))
                .andExpect(jsonPath("$.data[0].reason").value("HISTORY"));
        mockMvc.perform(getAuthed(url("SWIGGY ORDER 1234", "EXPENSE"), s.accessToken()))
                .andExpect(jsonPath("$.data[0].categoryId").value(dinner.toString()))
                .andExpect(jsonPath("$.data[1].categoryId").value(category(s, "FOOD_RESTAURANTS").toString()))
                .andExpect(jsonPath("$.data[1].reason").value("KEYWORD"));
    }

    @Test
    void knownMerchantsAndIncomeWords() throws Exception {
        Session s = register(uniqueEmail());
        mockMvc.perform(getAuthed(url("UBER INDIA TRIP", "EXPENSE"), s.accessToken()))
                .andExpect(jsonPath("$.data[0].categoryId").value(category(s, "TRANSPORT_CAB").toString()));
        mockMvc.perform(getAuthed(url("Netflix subscription", "EXPENSE"), s.accessToken()))
                .andExpect(jsonPath("$.data[0].categoryId").value(category(s, "ENTERTAINMENT_OTT").toString()));
        mockMvc.perform(getAuthed(url("NEFT ACME SALARY OCT", "INCOME"), s.accessToken()))
                .andExpect(jsonPath("$.data[0].categoryId").value(category(s, "INC_SALARY").toString()));
        // Income words never suggest expense categories and vice versa.
        mockMvc.perform(getAuthed(url("salary", "EXPENSE"), s.accessToken())).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(getAuthed(url("", "EXPENSE"), s.accessToken())).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void suggestionsNeverLeakAnotherUsersHistory() throws Exception {
        Session owner = register(uniqueEmail());
        expense(owner, "350", category(owner, "FOOD_DINNER"), "merchant", "Zxqv Unique Kitchen");
        Session other = register(uniqueEmail());
        mockMvc.perform(getAuthed(url("zxqv unique kitchen", "EXPENSE"), other.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
