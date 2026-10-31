package com.paisatrack.income;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class IncomeSourceIntegrationTest extends FinanceIntegrationTest {

    private static final YearMonth NOW = YearMonth.now(ZoneId.of("Asia/Kolkata"));

    private UUID salary(Session session, UUID accountId, String start, String amount) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Acme salary", "type", "SALARY",
                "accountId", accountId.toString(), "expectedAmount", amount, "expectedDay", 1));
        body.put("startMonth", start);
        return createdId(postAuthed("/api/v1/income-sources", session, body));
    }

    @Test
    void salarySourceDefaultsCategoryAndCurrency() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID id = salary(session, bank, NOW.toString(), "85000");

        mockMvc.perform(getAuthed("/api/v1/income-sources/" + id, session.accessToken()))
                .andExpect(jsonPath("$.data.category.name").value("Salary"))
                .andExpect(jsonPath("$.data.account.name").value("Bank"))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.currentExpectation.amount").value(85000.00))
                .andExpect(jsonPath("$.data.thisMonth.receivedAmount").value(0.00));
    }

    @Test
    void expectedSalaryIsNotIncomeUntilRecorded() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID id = salary(session, bank, NOW.toString(), "85000");

        mockMvc.perform(getAuthed("/api/v1/dashboard", session.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.income").value(0.00))
                .andExpect(jsonPath("$.data.expectedIncome[0].expectedAmount").value(85000.00))
                .andExpect(jsonPath("$.data.expectedIncome[0].receivedAmount").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/accounts/" + bank, session.accessToken()))
                .andExpect(jsonPath("$.data.currentBalance").value(0.00));

        // Recording it: no category needed, the source supplies it; account comes from the source too.
        postAuthed("/api/v1/income", session, body("amount", "85000", "incomeSourceId", id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.category.name").value("Salary"))
                .andExpect(jsonPath("$.data.account.name").value("Bank"))
                .andExpect(jsonPath("$.data.incomeSource.name").value("Acme salary"))
                .andExpect(jsonPath("$.data.incomePeriod").value(NOW.toString()));

        mockMvc.perform(getAuthed("/api/v1/income-sources/expected", session.accessToken()))
                .andExpect(jsonPath("$.data[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data[0].remainingAmount").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/dashboard", session.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.income").value(85000.00));
    }

    @Test
    void monthStatusesFollowPayPeriodNotCreditDate() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        YearMonth m0 = NOW.minusMonths(3);
        YearMonth m1 = NOW.minusMonths(2);
        YearMonth m2 = NOW.minusMonths(1);
        UUID id = salary(session, bank, m0.toString(), "50000");

        // m0's salary arrives on the 1st of the next month but settles m0.
        postAuthed("/api/v1/income", session, body("amount", "50000", "incomeSourceId", id,
                "transactionDate", m1.atDay(1).toString(), "periodMonth", m0.toString())).andExpect(status().isCreated());
        postAuthed("/api/v1/income", session, body("amount", "20000", "incomeSourceId", id,
                "transactionDate", m1.atDay(15).toString())).andExpect(status().isCreated());

        expectStatus(session, m0, "RECEIVED", "50000");
        expectStatus(session, m1, "PARTIALLY_RECEIVED", "20000");
        expectStatus(session, m2, "OVERDUE", "0");
        // Income reports still use the credit date: m1 holds both credits.
        mockMvc.perform(getAuthed("/api/v1/income?from=" + m1.atDay(1) + "&to=" + m1.atEndOfMonth(), session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(getAuthed("/api/v1/income?incomeSourceId=" + id, session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(2));
    }

    @Test
    void raisesApplyFromTheirMonthAndKeepHistory() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        YearMonth m0 = NOW.minusMonths(2);
        YearMonth m1 = NOW.minusMonths(1);
        UUID id = salary(session, bank, m0.toString(), "50000");

        putAuthed("/api/v1/income-sources/" + id + "/expectation", session,
                Map.of("amount", 60000, "effectiveFrom", m1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentExpectation.amount").value(60000.00));

        mockMvc.perform(getAuthed("/api/v1/income-sources/expected?month=" + m0, session.accessToken()))
                .andExpect(jsonPath("$.data[0].expectedAmount").value(50000.00));
        mockMvc.perform(getAuthed("/api/v1/income-sources/expected?month=" + m1, session.accessToken()))
                .andExpect(jsonPath("$.data[0].expectedAmount").value(60000.00));

        // Stop expecting it from this month: it drops out of this month's overview.
        Map<String, Object> stop = new HashMap<>();
        stop.put("amount", null);
        stop.put("effectiveFrom", NOW.toString());
        putAuthed("/api/v1/income-sources/" + id + "/expectation", session, stop).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/income-sources/expected", session.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(getAuthed("/api/v1/income-sources/" + id + "/history?year=" + NOW.getYear(), session.accessToken()))
                .andExpect(jsonPath("$.data[?(@.month == '" + NOW + "')].status", hasItem("NOT_EXPECTED")));
    }

    @Test
    void incomeLinkRulesAreEnforced() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID usd = createAccount(session, Map.of("name", "USD", "type", "SAVINGS", "currency", "USD"));
        UUID id = salary(session, bank, NOW.toString(), "1000");

        postAuthed("/api/v1/income", session, body("amount", "10", "categoryId", category(session, "INC_OTHER"),
                        "periodMonth", NOW.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("periodMonth"));
        postAuthed("/api/v1/income", session, body("amount", "10", "incomeSourceId", id, "accountId", usd))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("CURRENCY_MISMATCH"));
        postAuthed("/api/v1/income", session, body("amount", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("categoryId"));
        postAuthed("/api/v1/income-sources", session, Map.of("name", "acme SALARY", "type", "SALARY"))
                .andExpect(status().isConflict());
        mockMvc.perform(authed(post("/api/v1/income-sources/" + id + "/deactivate"), session.accessToken()))
                .andExpect(status().isOk());
        postAuthed("/api/v1/income", session, body("amount", "10", "incomeSourceId", id))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void categoryUsedByAnIncomeSourceCantBeDeleted() throws Exception {
        Session session = register(uniqueEmail());
        UUID gifts = category(session, "INC_GIFTS");
        postAuthed("/api/v1/income-sources", session, Map.of("name", "Family", "type", "GIFT")).andExpect(status().isCreated());

        mockMvc.perform(authed(delete("/api/v1/categories/" + gifts), session.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_IN_USE"));
    }

    @Test
    void incomeSourcesAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID id = salary(a, defaultAccount(a), NOW.toString(), "1000");

        mockMvc.perform(getAuthed("/api/v1/income-sources/" + id, b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/income-sources/" + id + "/history", b.accessToken())).andExpect(status().isNotFound());
        patchAuthed("/api/v1/income-sources/" + id, b, Map.of("name", "x")).andExpect(status().isNotFound());
        postAuthed("/api/v1/income", b, body("amount", "10", "incomeSourceId", id)).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/income-sources/expected", b.accessToken()))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private void expectStatus(Session session, YearMonth month, String status, String received) throws Exception {
        mockMvc.perform(getAuthed("/api/v1/income-sources/expected?month=" + month, session.accessToken()))
                .andExpect(jsonPath("$.data[0].month").value(month.toString()))
                .andExpect(jsonPath("$.data[0].status").value(status))
                .andExpect(jsonPath("$.data[0].receivedAmount").value(Double.parseDouble(received)))
                .andExpect(jsonPath("$.data[0].expectedDate").value(month.atDay(1).toString()));
    }
}
