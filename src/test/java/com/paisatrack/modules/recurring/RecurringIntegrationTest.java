package com.paisatrack.modules.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.paisatrack.support.FinanceIntegrationTest;

class RecurringIntegrationTest extends FinanceIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    RecurringOccurrenceJob job;

    @Autowired
    RecurringService service;

    private static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    private UUID rent(Session s, Object... extra) throws Exception {
        Map<String, Object> b = body("name", "House rent", "kind", "RENT", "type", "EXPENSE", "amount", "18000",
                "categoryId", category(s, "HOUSING_RENT"), "frequency", "MONTHLY");
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/recurring-transactions", s, b));
    }

    private List<Map<String, Object>> occurrences(Session s, UUID ruleId) throws Exception {
        return read("/api/v1/recurring-transactions/" + ruleId + "/occurrences", s, "$.data");
    }

    private String firstOccurrence(Session s, UUID ruleId) throws Exception {
        List<Map<String, Object>> all = occurrences(s, ruleId);
        return (String) all.getLast().get("id");
    }

    private ResultActions confirm(Session s, String occurrenceId, Object... body) throws Exception {
        return postAuthed("/api/v1/recurring-occurrences/" + occurrenceId + "/confirm", s, body(body));
    }

    @Test
    void expectedOccurrencesAreNotActualsUntilConfirmed() throws Exception {
        Session s = register(uniqueEmail());
        UUID cash = defaultAccount(s);
        UUID id = rent(s);
        // Next month's rent also falls inside the 30-day window when today is near a month end.
        boolean twoDue = !today().plusMonths(1).isAfter(today().plusDays(30));

        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.nextOccurrence.dueDate").value(today().toString()))
                .andExpect(jsonPath("$.data.nextOccurrence.status").value("DUE"))
                .andExpect(jsonPath("$.data.account.name").value("Cash"));
        // A plan only: no spending, no balance change, no transaction.
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/transactions", s.accessToken())).andExpect(jsonPath("$.data.totalItems").value(0));
        assertThat(balance(s, cash)).isEqualByComparingTo("0");
        mockMvc.perform(getAuthed("/api/v1/dashboard", s.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.expense").value(0.00))
                .andExpect(jsonPath("$.data.recurring.expectedOutflow").value(twoDue ? 36000.00 : 18000.00))
                .andExpect(jsonPath("$.data.recurring.upcoming[0].ruleName").value("House rent"));

        String occurrence = firstOccurrence(s, id);
        confirm(s, occurrence, "amount", "18500").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.actualAmount").value(18500.00))
                .andExpect(jsonPath("$.data.expectedAmount").value(18000.00));
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(18500.00));
        assertThat(balance(s, cash)).isEqualByComparingTo("-18500");
        mockMvc.perform(getAuthed("/api/v1/expenses", s.accessToken()))
                .andExpect(jsonPath("$.data.items[0].description").value("House rent"));
        confirm(s, occurrence).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("OCCURRENCE_RESOLVED"));
        mockMvc.perform(getAuthed("/api/v1/dashboard", s.accessToken()))
                .andExpect(jsonPath("$.data.recurring.expectedOutflow").value(twoDue ? 18000.00 : 0.00));
    }

    @Test
    void generationIsIdempotentAcrossRetriesAndParallelRuns() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = rent(s, "name", "Milk", "kind", "BILL", "amount", "60", "categoryId", category(s, "GROCERIES_DAIRY"),
                "frequency", "DAILY");
        assertThat(occurrences(s, id)).hasSize(RecurringService.HORIZON_DAYS + 1);

        job.generateAll();
        job.generateAll();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return service.generate(s.userId());
                }));
            }
            start.countDown();
            for (Future<Integer> run : runs) {
                assertThat(run.get()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> all = occurrences(s, id);
        assertThat(all).hasSize(RecurringService.HORIZON_DAYS + 1);
        assertThat(all.stream().map(o -> o.get("dueDate")).distinct().count()).isEqualTo(all.size());
    }

    @Test
    void schedulesStartFromTodayWithoutBackfill() throws Exception {
        Session s = register(uniqueEmail());
        UUID monthly = rent(s, "startDate", today().minusDays(10).toString());
        assertThat(occurrences(s, monthly)).extracting(o -> o.get("dueDate"))
                .containsExactly(today().minusDays(10).plusMonths(1).toString());

        UUID fortnightly = rent(s, "name", "Cleaner", "kind", "OTHER", "amount", "1500", "frequency", "WEEKLY", "interval", 2);
        assertThat(occurrences(s, fortnightly)).extracting(o -> o.get("dueDate"))
                .containsExactly(today().plusWeeks(4).toString(), today().plusWeeks(2).toString(), today().toString());

        UUID ending = rent(s, "name", "Gym", "kind", "SUBSCRIPTION", "amount", "999", "frequency", "WEEKLY",
                "endDate", today().plusDays(8).toString());
        assertThat(occurrences(s, ending)).hasSize(2);
    }

    @Test
    void pauseResumeAndStop() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = rent(s, "frequency", "WEEKLY");
        assertThat(occurrences(s, id)).hasSize(6);

        mockMvc.perform(authed(post("/api/v1/recurring-transactions/" + id + "/pause"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.nextOccurrence").doesNotExist());
        assertThat(occurrences(s, id)).isEmpty();
        job.generateAll();
        assertThat(occurrences(s, id)).isEmpty();

        mockMvc.perform(authed(post("/api/v1/recurring-transactions/" + id + "/resume"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.nextOccurrence.dueDate").value(today().toString()));
        assertThat(occurrences(s, id)).hasSize(6);

        patchAuthed("/api/v1/recurring-transactions/" + id, s, body("amount", "20000"))
                .andExpect(jsonPath("$.data.amount").value(20000.00))
                .andExpect(jsonPath("$.data.nextOccurrence.expectedAmount").value(20000.00));

        mockMvc.perform(authed(post("/api/v1/recurring-transactions/" + id + "/stop"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("STOPPED"));
        assertThat(occurrences(s, id)).isEmpty();
        mockMvc.perform(authed(post("/api/v1/recurring-transactions/" + id + "/resume"), s.accessToken()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECURRING_STOPPED"));
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions", s.accessToken())).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions?includeStopped=true", s.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + id + "/history", s.accessToken()))
                .andExpect(jsonPath("$.data[*].action").value(org.hamcrest.Matchers.hasItems("CREATED", "PAUSED", "RESUMED", "UPDATED", "STOPPED")));
    }

    @Test
    void overdueOccurrencesStayUntilConfirmedOrSkipped() throws Exception {
        Session s = register(uniqueEmail());
        setTimezone(s, "Pacific/Pago_Pago");
        UUID id = rent(s, "frequency", "YEARLY");
        String occurrence = firstOccurrence(s, id);
        LocalDate due = LocalDate.now(ZoneId.of("Pacific/Pago_Pago"));
        // Same instant, a day or two later: the occurrence is now overdue for this user.
        setTimezone(s, "Pacific/Kiritimati");
        job.generateAll();

        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].overdueCount").value(1))
                .andExpect(jsonPath("$.data.expected[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.data.expected[0].dueDate").value(due.toString()));
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.overdueCount").value(1));

        postAuthed("/api/v1/recurring-occurrences/" + occurrence + "/skip", s, body("reason", "Paid in cash to landlord's son"))
                .andExpect(jsonPath("$.data.status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.note").value("Paid in cash to landlord's son"));
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.expected", hasSize(0)));
        mockMvc.perform(authed(post("/api/v1/recurring-occurrences/" + occurrence + "/reopen"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("OVERDUE"));
    }

    @Test
    void voidingOrReopeningTheTransactionReopensTheOccurrence() throws Exception {
        Session s = register(uniqueEmail());
        UUID cash = defaultAccount(s);
        UUID id = rent(s);
        String occurrence = firstOccurrence(s, id);
        String tx = JsonPath.read(confirm(s, occurrence).andReturn().getResponse().getContentAsString(), "$.data.transactionId");

        mockMvc.perform(authed(post("/api/v1/transactions/" + tx + "/void"), s.accessToken())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/recurring-occurrences", s.accessToken()))
                .andExpect(jsonPath("$.data[0].status").value("DUE"))
                .andExpect(jsonPath("$.data[0].transactionId").doesNotExist());
        mockMvc.perform(authed(post("/api/v1/transactions/" + tx + "/restore"), s.accessToken())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/recurring-occurrences", s.accessToken()))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].actualAmount").value(18000.00));
        putAuthed("/api/v1/expenses/" + tx, s, body("amount", "17000", "categoryId", category(s, "HOUSING_RENT"),
                "accountId", cash, "transactionDate", today().toString())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/recurring-occurrences", s.accessToken()))
                .andExpect(jsonPath("$.data[0].actualAmount").value(17000.00));

        mockMvc.perform(authed(post("/api/v1/recurring-occurrences/" + occurrence + "/reopen"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("DUE"));
        assertThat(balance(s, cash)).isEqualByComparingTo("0");
        mockMvc.perform(getAuthed("/api/v1/transactions/" + tx, s.accessToken())).andExpect(jsonPath("$.data.status").value("VOIDED"));
        // The reopened link is gone: restoring that old transaction doesn't confirm the occurrence again.
        mockMvc.perform(authed(post("/api/v1/transactions/" + tx + "/restore"), s.accessToken())).andExpect(status().isOk());
        mockMvc.perform(getAuthed("/api/v1/recurring-occurrences", s.accessToken())).andExpect(jsonPath("$.data[0].status").value("DUE"));
    }

    @Test
    void transfersAndIncomeConfirmAsTheirOwnTypes() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "50000");
        UUID invest = createAccount(s, "Mutual funds", "OTHER", "0");
        UUID sip = createdId(postAuthed("/api/v1/recurring-transactions", s, body("name", "Index fund SIP", "kind", "INVESTMENT",
                "type", "TRANSFER", "amount", "5000", "accountId", bank, "toAccountId", invest, "frequency", "MONTHLY")));
        UUID rentIn = createdId(postAuthed("/api/v1/recurring-transactions", s, body("name", "Flat rent received", "kind", "RENT",
                "type", "INCOME", "amount", "12000", "accountId", bank, "categoryId", category(s, "INC_RENTAL"), "frequency", "MONTHLY")));

        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/summary?days=7", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].expectedOutflow").value(5000.00))
                .andExpect(jsonPath("$.data.currencies[0].expectedInflow").value(12000.00))
                .andExpect(jsonPath("$.data.currencies[0].dueCount").value(2));
        confirm(s, firstOccurrence(s, sip)).andExpect(status().isOk());
        confirm(s, firstOccurrence(s, rentIn)).andExpect(status().isOk());

        assertThat(balance(s, bank)).isEqualByComparingTo("57000");
        assertThat(balance(s, invest)).isEqualByComparingTo("5000");
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00))
                .andExpect(jsonPath("$.data.currencies[0].totalIncome").value(12000.00));
    }

    @Test
    void validationAndDeletion() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "0");
        postAuthed("/api/v1/recurring-transactions", s, body("name", "SIP", "kind", "INVESTMENT", "type", "TRANSFER", "amount", "1",
                "accountId", bank, "frequency", "MONTHLY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("toAccountId"));
        postAuthed("/api/v1/recurring-transactions", s, body("name", "Rent", "kind", "RENT", "type", "EXPENSE", "amount", "1",
                "frequency", "MONTHLY"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("categoryId"));
        postAuthed("/api/v1/recurring-transactions", s, body("name", "Rent", "kind", "RENT", "type", "EXPENSE", "amount", "1",
                "categoryId", category(s, "INC_SALARY"), "frequency", "MONTHLY"))
                .andExpect(status().isBadRequest());
        UUID neverConfirmed = rent(s, "endDate", today().plusMonths(3).toString());
        patchAuthed("/api/v1/recurring-transactions/" + neverConfirmed, s, body("endDate", today().minusDays(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("endDate"));
        postAuthed("/api/v1/recurring-transactions", s, body("name", "Rent", "kind", "RENT", "type", "EXPENSE", "amount", "1",
                "categoryId", category(s, "HOUSING_RENT"), "frequency", "MONTHLY", "interval", 0))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authed(delete("/api/v1/categories/" + category(s, "HOUSING_RENT")), s.accessToken()))
                .andExpect(status().isConflict());
        UUID confirmed = rent(s, "name", "Rent 2");
        confirm(s, firstOccurrence(s, confirmed)).andExpect(status().isOk());
        mockMvc.perform(authed(delete("/api/v1/recurring-transactions/" + confirmed), s.accessToken()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RECURRING_HAS_CONFIRMED"));
        mockMvc.perform(authed(delete("/api/v1/recurring-transactions/" + neverConfirmed), s.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + neverConfirmed, s.accessToken())).andExpect(status().isNotFound());
    }

    @Test
    void parallelConfirmationsRecordOneTransaction() throws Exception {
        Session s = register(uniqueEmail());
        String occurrence = firstOccurrence(s, rent(s));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return confirm(s, occurrence).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
        } finally {
            pool.shutdownNow();
        }
        mockMvc.perform(getAuthed("/api/v1/transactions", s.accessToken())).andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void retriedCreateWithTheSameKeyMakesOneRule() throws Exception {
        Session s = register(uniqueEmail());
        Map<String, Object> b = body("name", "Netflix", "kind", "SUBSCRIPTION", "type", "EXPENSE", "amount", "649",
                "categoryId", category(s, "ENTERTAINMENT_OTT"), "frequency", "MONTHLY");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(authed(post("/api/v1/recurring-transactions"), s.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "netflix-1").content(json(b)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions", s.accessToken())).andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void rulesAndOccurrencesAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID id = rent(a);
        String occurrence = firstOccurrence(a, id);

        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + id, b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/recurring-transactions/" + id + "/occurrences", b.accessToken())).andExpect(status().isNotFound());
        confirm(b, occurrence).andExpect(status().isNotFound());
        postAuthed("/api/v1/recurring-occurrences/" + occurrence + "/skip", b, Map.of()).andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/recurring-transactions/" + id + "/pause"), b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/recurring-occurrences", b.accessToken())).andExpect(jsonPath("$.data", hasSize(0)));
        // Another user's account can't be used either.
        postAuthed("/api/v1/recurring-transactions", b, body("name", "X", "kind", "BILL", "type", "EXPENSE", "amount", "1",
                "accountId", defaultAccount(a), "categoryId", category(b, "UTILITIES_INTERNET"), "frequency", "MONTHLY"))
                .andExpect(status().isNotFound());
    }

    private void setTimezone(Session s, String zone) throws Exception {
        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), s.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("timezone", zone))))
                .andExpect(status().isOk());
    }
}
