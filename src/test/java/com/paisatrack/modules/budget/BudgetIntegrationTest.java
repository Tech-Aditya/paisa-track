package com.paisatrack.modules.budget;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class BudgetIntegrationTest extends FinanceIntegrationTest {

    /** A Saturday; budgets start well before it so they're in effect. */
    private static final String DAY = "2026-03-14";
    private static final String START = "2026-01-01";

    private UUID budget(Session session, Object... keyValues) throws Exception {
        Map<String, Object> b = body(keyValues);
        b.putIfAbsent("startDate", START);
        return createdId(postAuthed("/api/v1/budgets", session, b));
    }

    @Test
    void categoryBudgetCountsOnlyRealSpendingInItsCategoryTree() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID usd = createAccount(session, Map.of("name", "USD", "type", "CASH", "currency", "USD"));
        UUID food = category(session, "FOOD");
        UUID id = budget(session, "scope", "CATEGORY", "categoryId", food, "period", "MONTHLY", "amount", "1000");

        expense(session, "300", category(session, "FOOD_LUNCH"), "transactionDate", DAY);
        expense(session, "500", category(session, "FOOD_DINNER"), "transactionDate", "2026-03-01");
        expense(session, "999", category(session, "TRANSPORT_CAB"), "transactionDate", DAY);       // other category
        expense(session, "50", category(session, "FOOD"), "transactionDate", "2026-02-28");         // previous month
        expense(session, "70", category(session, "FOOD"), "transactionDate", DAY, "accountId", usd); // other currency
        UUID voided = expense(session, "400", category(session, "FOOD_SNACKS"), "transactionDate", DAY);
        mockMvc.perform(authed(post("/api/v1/transactions/" + voided + "/void"), session.accessToken())).andExpect(status().isOk());
        income(session, "5000", category(session, "INC_SALARY"), "transactionDate", DAY, "accountId", bank);
        transfer(session, "200", bank, defaultAccount(session), "transactionDate", DAY);

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.name").value("Food & Dining (monthly)"))
                .andExpect(jsonPath("$.data.utilization.periodStart").value("2026-03-01"))
                .andExpect(jsonPath("$.data.utilization.periodEnd").value("2026-03-31"))
                .andExpect(jsonPath("$.data.utilization.spent").value(800.00))
                .andExpect(jsonPath("$.data.utilization.remaining").value(200.00))
                .andExpect(jsonPath("$.data.utilization.percentUsed").value(80.00))
                .andExpect(jsonPath("$.data.utilization.status").value("ALERT"))
                .andExpect(jsonPath("$.data.utilization.crossedThresholds", contains(75)))
                .andExpect(jsonPath("$.data.utilization.daysLeft").value(18))
                .andExpect(jsonPath("$.data.utilization.dailyAllowance").value(11.11));

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=2026-02-10", session.accessToken()))
                .andExpect(jsonPath("$.data.utilization.spent").value(50.00))
                .andExpect(jsonPath("$.data.utilization.status").value("ON_TRACK"));
    }

    @Test
    void overspendingIsReportedAsExceeded() throws Exception {
        Session session = register(uniqueEmail());
        UUID id = budget(session, "scope", "OVERALL", "period", "MONTHLY", "amount", "500", "alertThresholds", List.of(50, 100));
        expense(session, "650", category(session, "SHOPPING"), "transactionDate", DAY);

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.name").value("Monthly budget"))
                .andExpect(jsonPath("$.data.utilization.status").value("EXCEEDED"))
                .andExpect(jsonPath("$.data.utilization.remaining").value(-150.00))
                .andExpect(jsonPath("$.data.utilization.percentUsed").value(130.00))
                .andExpect(jsonPath("$.data.utilization.crossedThresholds", contains(50, 100)))
                .andExpect(jsonPath("$.data.utilization.dailyAllowance").value(0.00));
    }

    @Test
    void weeklyBudgetFollowsTheWeekStartPreference() throws Exception {
        Session session = register(uniqueEmail());
        patchAuthed("/api/v1/users/me/preferences", session, Map.of("weekStartDay", "SUNDAY")).andExpect(status().isOk());
        UUID id = budget(session, "scope", "OVERALL", "period", "WEEKLY", "amount", "1000");
        expense(session, "100", category(session, "FOOD"), "transactionDate", "2026-03-07"); // Saturday, previous week
        expense(session, "200", category(session, "FOOD"), "transactionDate", "2026-03-08"); // Sunday, this week

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.utilization.periodStart").value("2026-03-08"))
                .andExpect(jsonPath("$.data.utilization.periodEnd").value("2026-03-14"))
                .andExpect(jsonPath("$.data.utilization.spent").value(200.00))
                .andExpect(jsonPath("$.data.utilization.daysLeft").value(1));
    }

    @Test
    void customRangeBudgetAndNotInEffectOutsideIt() throws Exception {
        Session session = register(uniqueEmail());
        UUID id = budget(session, "scope", "OVERALL", "period", "CUSTOM", "amount", "20000",
                "startDate", "2026-03-10", "endDate", "2026-03-20", "name", "Goa trip");
        expense(session, "5000", category(session, "TRAVEL"), "transactionDate", "2026-03-12");
        expense(session, "999", category(session, "TRAVEL"), "transactionDate", "2026-03-21");

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.utilization.spent").value(5000.00))
                .andExpect(jsonPath("$.data.utilization.status").value("ON_TRACK"));
        mockMvc.perform(getAuthed("/api/v1/budgets/" + id + "?date=2026-04-01", session.accessToken()))
                .andExpect(jsonPath("$.data.utilization.status").value("NOT_IN_EFFECT"));
    }

    @Test
    void budgetRulesAreEnforced() throws Exception {
        Session session = register(uniqueEmail());
        UUID food = category(session, "FOOD");
        budget(session, "scope", "CATEGORY", "categoryId", food, "period", "MONTHLY", "amount", "1000");

        postAuthed("/api/v1/budgets", session, body("scope", "CATEGORY", "categoryId", food, "period", "MONTHLY", "amount", "2000"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_BUDGET"));
        postAuthed("/api/v1/budgets", session, body("scope", "CATEGORY", "period", "MONTHLY", "amount", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("categoryId"));
        postAuthed("/api/v1/budgets", session, body("scope", "OVERALL", "categoryId", food, "period", "MONTHLY", "amount", "1"))
                .andExpect(status().isBadRequest());
        postAuthed("/api/v1/budgets", session, body("scope", "CATEGORY", "categoryId", category(session, "INC_SALARY"),
                "period", "MONTHLY", "amount", "1")).andExpect(status().isBadRequest());
        postAuthed("/api/v1/budgets", session, body("scope", "OVERALL", "period", "CUSTOM", "amount", "1", "startDate", START))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("endDate"));
        postAuthed("/api/v1/budgets", session, body("scope", "OVERALL", "period", "MONTHLY", "amount", "1",
                "alertThresholds", List.of(90, 90))).andExpect(status().isBadRequest());
        postAuthed("/api/v1/budgets", session, body("scope", "OVERALL", "period", "MONTHLY", "amount", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("amount"));
    }

    @Test
    void updateDeactivateAndDelete() throws Exception {
        Session session = register(uniqueEmail());
        UUID id = budget(session, "scope", "OVERALL", "period", "MONTHLY", "amount", "1000");

        patchAuthed("/api/v1/budgets/" + id, session, body("amount", "1500", "alertThresholds", List.of(80)))
                .andExpect(jsonPath("$.data.amount").value(1500.00))
                .andExpect(jsonPath("$.data.alertThresholds", contains(80)));
        mockMvc.perform(authed(post("/api/v1/budgets/" + id + "/deactivate"), session.accessToken()))
                .andExpect(jsonPath("$.data.utilization.status").value("NOT_IN_EFFECT"));
        // With the old one paused, a new monthly overall budget is allowed; reactivating the old one is not.
        budget(session, "scope", "OVERALL", "period", "MONTHLY", "amount", "900");
        mockMvc.perform(authed(post("/api/v1/budgets/" + id + "/activate"), session.accessToken()))
                .andExpect(status().isConflict());
        mockMvc.perform(authed(delete("/api/v1/budgets/" + id), session.accessToken())).andExpect(status().isNoContent());
        mockMvc.perform(getAuthed("/api/v1/budgets/" + id, session.accessToken())).andExpect(status().isNotFound());
    }

    @Test
    void budgetsAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID id = budget(a, "scope", "OVERALL", "period", "MONTHLY", "amount", "1000");

        mockMvc.perform(getAuthed("/api/v1/budgets/" + id, b.accessToken())).andExpect(status().isNotFound());
        patchAuthed("/api/v1/budgets/" + id, b, body("amount", "1")).andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/budgets/" + id), b.accessToken())).andExpect(status().isNotFound());
        postAuthed("/api/v1/budgets", b, body("scope", "CATEGORY", "categoryId", category(a, "FOOD"), "period", "MONTHLY", "amount", "1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/budgets", b.accessToken())).andExpect(jsonPath("$.data.length()").value(0));
    }
}
