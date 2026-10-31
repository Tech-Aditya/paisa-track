package com.paisatrack.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class DashboardIntegrationTest extends FinanceIntegrationTest {

    @Test
    void figuresReconcileWithTransactionRecords() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "10000");
        UUID usd = createAccount(session, Map.of("name", "USD", "type", "CASH", "currency", "USD"));
        expense(session, "150", category(session, "FOOD_LUNCH"));
        expense(session, "40", category(session, "FOOD_TEA_COFFEE"));
        expense(session, "1200", category(session, "GROCERIES"), "accountId", bank);
        income(session, "5000", category(session, "INC_FREELANCE"), "accountId", bank);
        transfer(session, "2000", bank, defaultAccount(session));
        expense(session, "30", category(session, "FOOD"), "accountId", usd);
        postAuthed("/api/v1/income-sources", session, body("name", "Salary", "type", "SALARY", "expectedAmount", "90000"))
                .andExpect(status().isCreated());

        String json = getJson("/api/v1/dashboard", session);
        String today = com.jayway.jsonpath.JsonPath.read(json, "$.data.today");
        mockMvc.perform(getAuthed("/api/v1/dashboard", session.accessToken()))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.periods.today.expense").value(1390.00))
                .andExpect(jsonPath("$.data.periods.today.expenseCount").value(3))
                .andExpect(jsonPath("$.data.periods.today.income").value(5000.00))
                .andExpect(jsonPath("$.data.periods.month.income").value(5000.00)) // expected salary not counted
                .andExpect(jsonPath("$.data.periods.month.netCashFlow").value(3610.00))
                .andExpect(jsonPath("$.data.savingsRate").value(72.20))
                .andExpect(jsonPath("$.data.otherCurrencyTransactions").value(1))
                .andExpect(jsonPath("$.data.accounts.totalAssets").value(13610.00))
                .andExpect(jsonPath("$.data.accounts.netWorth").value(13610.00))
                .andExpect(jsonPath("$.data.expectedIncome[0].expectedAmount").value(90000.00))
                .andExpect(jsonPath("$.data.expenseByCategory[0].name").value("Groceries"))
                .andExpect(jsonPath("$.data.monthlyTrend", hasSize(6)))
                .andExpect(jsonPath("$.data.monthlyTrend[5].month").value(YearMonth.parse(today.substring(0, 7)).toString()))
                .andExpect(jsonPath("$.data.monthlyTrend[5].expense").value(1390.00))
                .andExpect(jsonPath("$.data.recentTransactions", hasSize(6)));

        // Same numbers as the daily summary and the expense list.
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", session.accessToken()))
                .andExpect(jsonPath("$.data.currencies[?(@.currency == 'INR')].totalExpense").value(1390.00));
        List<Double> listed = read("/api/v1/expenses?from=" + today + "&to=" + today, session,
                "$.data.items[?(@.currency == 'INR')].amount");
        assertThat(listed.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1390");
        // Daily chart: today's bar matches.
        List<Double> todayBar = read("/api/v1/dashboard", session, "$.data.dailySpending[?(@.date == '" + today + "')].amount");
        assertThat(todayBar).containsExactly(1390.0);
    }

    @Test
    void selectedRangeDrivesChartsAndIsValidated() throws Exception {
        Session session = register(uniqueEmail());
        expense(session, "100", category(session, "FOOD"), "transactionDate", "2026-03-02");
        expense(session, "300", category(session, "TRAVEL"), "transactionDate", "2026-03-04");

        mockMvc.perform(getAuthed("/api/v1/dashboard?from=2026-03-01&to=2026-03-07", session.accessToken()))
                .andExpect(jsonPath("$.data.periods.selected.expense").value(400.00))
                .andExpect(jsonPath("$.data.dailySpending", hasSize(7)))
                .andExpect(jsonPath("$.data.dailySpending[1].amount").value(100.00))
                .andExpect(jsonPath("$.data.dailySpending[2].amount").value(0.00))
                .andExpect(jsonPath("$.data.expenseByCategory[0].name").value("Travel"))
                .andExpect(jsonPath("$.data.expenseByCategory[0].share").value(75.0))
                .andExpect(jsonPath("$.data.expenseByCategory[1].share").value(25.0));
        mockMvc.perform(getAuthed("/api/v1/dashboard/category-spending?from=2026-03-01&to=2026-03-07", session.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(2)));

        mockMvc.perform(getAuthed("/api/v1/dashboard?from=2026-03-07&to=2026-03-01", session.accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(getAuthed("/api/v1/dashboard?from=2025-01-01&to=2026-03-01", session.accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(getAuthed("/api/v1/dashboard?from=2026-03-01", session.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("to"));
    }

    @Test
    void recentTransactionsAreNewestFirstAndLimited() throws Exception {
        Session session = register(uniqueEmail());
        for (int i = 1; i <= 4; i++) {
            expense(session, String.valueOf(i * 10), category(session, "FOOD"), "transactionDate", "2026-03-0" + i);
        }
        mockMvc.perform(getAuthed("/api/v1/dashboard/recent-transactions?limit=2", session.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].transactionDate").value("2026-03-04"));
        mockMvc.perform(getAuthed("/api/v1/dashboard/recent-transactions?limit=500", session.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weekCardUsesWeekStartPreference() throws Exception {
        Session session = register(uniqueEmail());
        patchAuthed("/api/v1/users/me/preferences", session, Map.of("weekStartDay", "WEDNESDAY")).andExpect(status().isOk());
        String from = read("/api/v1/dashboard", session, "$.data.periods.week.from");
        assertThat(LocalDate.parse(from).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.WEDNESDAY);
        assertThat(LocalDate.parse(from)).isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(7));
    }

    @Test
    void dashboardIsPrivateButTheWebAppIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/")).andExpect(redirectedUrl("/app/"));
        mockMvc.perform(get("/app/")).andExpect(status().isOk()).andExpect(forwardedUrl("/app/index.html"));
        mockMvc.perform(get("/app/js/main.js")).andExpect(status().isOk());
        mockMvc.perform(get("/app/css/app.css")).andExpect(status().isOk());
    }
}
