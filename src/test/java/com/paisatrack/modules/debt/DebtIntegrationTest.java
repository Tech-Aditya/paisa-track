package com.paisatrack.modules.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.paisatrack.support.FinanceIntegrationTest;

class DebtIntegrationTest extends FinanceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private UUID lend(Session s, String person, String amount, Object... extra) throws Exception {
        Map<String, Object> b = body("personName", person, "amount", amount);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/lending", s, b));
    }

    private UUID borrow(Session s, String person, String amount, Object... extra) throws Exception {
        Map<String, Object> b = body("personName", person, "amount", amount);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/borrowing", s, b));
    }

    private org.springframework.test.web.servlet.ResultActions repay(Session s, String path, UUID id, String amount, Object... extra)
            throws Exception {
        Map<String, Object> b = body("amount", amount);
        b.putAll(body(extra));
        return postAuthed("/api/v1/" + path + "/" + id + "/repayments", s, b);
    }

    @Test
    void lendingWithPartialRepaymentKeepsOriginalAmount() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "10000", "description", "Laptop");

        mockMvc.perform(getAuthed("/api/v1/lending/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.direction").value("LENT"))
                .andExpect(jsonPath("$.data.person.name").value("Rahul"))
                .andExpect(jsonPath("$.data.outstanding").value(10000.00))
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        repay(s, "lending", id, "3000").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.principal").value(10000.00))
                .andExpect(jsonPath("$.data.repaid").value(3000.00))
                .andExpect(jsonPath("$.data.outstanding").value(7000.00))
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_REPAID"))
                .andExpect(jsonPath("$.data.repayments", hasSize(1)))
                .andExpect(jsonPath("$.data.repayments[0].amount").value(3000.00));
    }

    @Test
    void borrowingRepaidInFullIsSettledAndClosed() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = borrow(s, "Amit", "15000");

        repay(s, "borrowing", id, "5000").andExpect(jsonPath("$.data.outstanding").value(10000.00));
        repay(s, "borrowing", id, "10000")
                .andExpect(jsonPath("$.data.outstanding").value(0.00))
                .andExpect(jsonPath("$.data.status").value("SETTLED"));
        repay(s, "borrowing", id, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("DEBT_CLOSED"));
    }

    @Test
    void repaymentCannotExceedOutstanding() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "1000");
        repay(s, "lending", id, "600").andExpect(status().isCreated());

        repay(s, "lending", id, "400.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("REPAYMENT_EXCEEDS_OUTSTANDING"));
        mockMvc.perform(getAuthed("/api/v1/lending/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.outstanding").value(400.00));
    }

    @Test
    void principalIsNeverIncomeOrExpenseAndBalancesAreUntouched() throws Exception {
        Session s = register(uniqueEmail());
        UUID cash = defaultAccount(s);
        UUID lent = lend(s, "Rahul", "10000");
        UUID borrowed = borrow(s, "Amit", "15000");
        repay(s, "lending", lent, "3000").andExpect(status().isCreated());
        repay(s, "borrowing", borrowed, "5000").andExpect(status().isCreated());

        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalIncome").value(0.00))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/dashboard", s.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.income").value(0.00))
                .andExpect(jsonPath("$.data.periods.month.expense").value(0.00))
                .andExpect(jsonPath("$.data.accounts.netWorth").value(0.00))
                .andExpect(jsonPath("$.data.debts.owedToYou").value(7000.00))
                .andExpect(jsonPath("$.data.debts.youOwe").value(10000.00));
        assertThat(balance(s, cash)).isEqualByComparingTo("0");
        mockMvc.perform(getAuthed("/api/v1/transactions", s.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(getAuthed("/api/v1/debts/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].owedToYou").value(7000.00))
                .andExpect(jsonPath("$.data.currencies[0].youOwe").value(10000.00))
                .andExpect(jsonPath("$.data.currencies[0].openLent").value(1))
                .andExpect(jsonPath("$.data.currencies[0].openBorrowed").value(1));
    }

    @Test
    void concurrentRepaymentsCannotOvershoot() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "10000");
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return repay(s, "lending", id, "3000").andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(code -> code == 201).hasSize(3);
            assertThat(statuses).filteredOn(code -> code == 422).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
        mockMvc.perform(getAuthed("/api/v1/lending/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.repaid").value(9000.00))
                .andExpect(jsonPath("$.data.outstanding").value(1000.00));
    }

    @Test
    void voidingARepaymentKeepsItInHistory() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "10000");
        String json = repay(s, "lending", id, "3000").andReturn().getResponse().getContentAsString();
        String repaymentId = JsonPath.read(json, "$.data.repayments[0].id");

        for (int i = 0; i < 2; i++) {
            postAuthed("/api/v1/repayments/" + repaymentId + "/void", s, Map.of("reason", "Entered twice"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.outstanding").value(10000.00))
                    .andExpect(jsonPath("$.data.repayments[0].voided").value(true))
                    .andExpect(jsonPath("$.data.repayments[0].voidReason").value("Entered twice"));
        }
        List<String> actions = read("/api/v1/lending/" + id + "/history", s, "$.data[*].action");
        assertThat(actions).containsExactly("CREATED", "REPAID", "REPAYMENT_VOIDED");
    }

    @Test
    void overdueRecordsAppearInReminders() throws Exception {
        Session s = register(uniqueEmail());
        UUID late = lend(s, "Rahul", "500", "startDate", TODAY.minusDays(30).toString(), "dueDate", TODAY.minusDays(5).toString());
        UUID later = borrow(s, "Amit", "800", "dueDate", TODAY.plusDays(30).toString());
        lend(s, "Neha", "300");

        mockMvc.perform(getAuthed("/api/v1/lending/" + late, s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("OVERDUE"))
                .andExpect(jsonPath("$.data.overdue").value(true))
                .andExpect(jsonPath("$.data.daysOverdue").value(5));
        List<String> soon = read("/api/v1/debts/reminders", s, "$.data[*].id");
        assertThat(soon).containsExactly(late.toString());
        List<String> month = read("/api/v1/debts/reminders?days=40", s, "$.data[*].id");
        assertThat(month).containsExactly(late.toString(), later.toString());
        mockMvc.perform(getAuthed("/api/v1/lending?status=OVERDUE", s.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(getAuthed("/api/v1/debts/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].overdueOwedToYou").value(500.00))
                .andExpect(jsonPath("$.data.currencies[0].overdueCount").value(1));
    }

    @Test
    void writeOffClosesWithoutCountingAsIncomeOrExpense() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "1000");
        repay(s, "lending", id, "400");

        postAuthed("/api/v1/lending/" + id + "/write-off", s, Map.of("reason", "Moved abroad"))
                .andExpect(jsonPath("$.data.status").value("WRITTEN_OFF"))
                .andExpect(jsonPath("$.data.writtenOff").value(600.00))
                .andExpect(jsonPath("$.data.outstanding").value(0.00));
        repay(s, "lending", id, "1").andExpect(jsonPath("$.error.code").value("DEBT_CLOSED"));
        mockMvc.perform(getAuthed("/api/v1/dashboard", s.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.expense").value(0.00))
                .andExpect(jsonPath("$.data.debts.owedToYou").value(0.00));

        mockMvc.perform(authed(post("/api/v1/lending/" + id + "/reopen"), s.accessToken()))
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_REPAID"))
                .andExpect(jsonPath("$.data.outstanding").value(600.00));
    }

    @Test
    void editingTheAmountCannotGoBelowWhatWasRepaid() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "1000");
        repay(s, "lending", id, "400");

        patchAuthed("/api/v1/lending/" + id, s, body("amount", "300"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("amount"));
        patchAuthed("/api/v1/lending/" + id, s, body("amount", "1200", "dueDate", TODAY.plusDays(10).toString()))
                .andExpect(jsonPath("$.data.outstanding").value(800.00))
                .andExpect(jsonPath("$.data.dueDate").value(TODAY.plusDays(10).toString()));
        patchAuthed("/api/v1/lending/" + id, s, Map.of("clearDueDate", true))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
        mockMvc.perform(getAuthed("/api/v1/lending/" + id + "/history", s.accessToken()))
                .andExpect(jsonPath("$.data[?(@.action == 'UPDATED')].changes.principal.to").value(org.hamcrest.Matchers.hasItem("1200")));
    }

    @Test
    void onlyRecordsWithoutRepaymentsCanBeDeleted() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "1000");
        String json = repay(s, "lending", id, "100").andReturn().getResponse().getContentAsString();
        String repaymentId = JsonPath.read(json, "$.data.repayments[0].id");

        mockMvc.perform(authed(delete("/api/v1/lending/" + id), s.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DEBT_HAS_REPAYMENTS"));
        postAuthed("/api/v1/repayments/" + repaymentId + "/void", s, Map.of()).andExpect(status().isOk());
        mockMvc.perform(authed(delete("/api/v1/lending/" + id), s.accessToken())).andExpect(status().isNoContent());
        mockMvc.perform(getAuthed("/api/v1/lending/" + id, s.accessToken())).andExpect(status().isNotFound());

        String personId = read("/api/v1/people", s, "$.data[0].id");
        mockMvc.perform(authed(delete("/api/v1/people/" + personId), s.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PERSON_IN_USE"));
    }

    @Test
    void retriesWithTheSameKeyRecordOneRepayment() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = lend(s, "Rahul", "1000");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(authed(post("/api/v1/lending/" + id + "/repayments"), s.accessToken())
                            .header("Idempotency-Key", "repay-key-0001")
                            .contentType(MediaType.APPLICATION_JSON).content(json(body("amount", "250"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.outstanding").value(750.00));
        }
    }

    @Test
    void contactsAreMatchedByNameAndManaged() throws Exception {
        Session s = register(uniqueEmail());
        lend(s, "Rahul Sharma", "100");
        borrow(s, "rahul sharma", "50");
        List<String> names = read("/api/v1/people", s, "$.data[*].name");
        assertThat(names).containsExactly("Rahul Sharma");

        UUID neha = createdId(postAuthed("/api/v1/people", s, Map.of("name", "Neha", "phone", "+91 98765 43210")));
        postAuthed("/api/v1/people", s, Map.of("name", "NEHA")).andExpect(status().isConflict());
        patchAuthed("/api/v1/people/" + neha, s, Map.of("email", "neha@example.com"))
                .andExpect(jsonPath("$.data.email").value("neha@example.com"));
        mockMvc.perform(authed(delete("/api/v1/people/" + neha), s.accessToken())).andExpect(status().isNoContent());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        Session s = register(uniqueEmail());
        postAuthed("/api/v1/lending", s, body("personName", "Rahul", "amount", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("amount"));
        postAuthed("/api/v1/lending", s, body("amount", "10"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("personName"));
        postAuthed("/api/v1/lending", s, body("personName", "Rahul", "amount", "10",
                        "startDate", TODAY.toString(), "dueDate", TODAY.minusDays(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("dueDate"));
        postAuthed("/api/v1/lending", s, body("personName", "Rahul", "amount", "10", "startDate", TODAY.plusDays(5).toString()))
                .andExpect(status().isBadRequest());
        UUID id = lend(s, "Rahul", "100", "startDate", TODAY.minusDays(3).toString());
        repay(s, "lending", id, "10", "repaymentDate", TODAY.minusDays(10).toString())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("repaymentDate"));
    }

    @Test
    void recordsAreIsolatedAndDirectionMatters() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID id = lend(a, "Rahul", "1000");
        String json = repay(a, "lending", id, "100").andReturn().getResponse().getContentAsString();
        String repaymentId = JsonPath.read(json, "$.data.repayments[0].id");

        mockMvc.perform(getAuthed("/api/v1/borrowing/" + id, a.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/lending/" + id, b.accessToken())).andExpect(status().isNotFound());
        repay(b, "lending", id, "10").andExpect(status().isNotFound());
        postAuthed("/api/v1/repayments/" + repaymentId + "/void", b, Map.of()).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/repayments/" + repaymentId, b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/lending", b.accessToken())).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(getAuthed("/api/v1/people", b.accessToken())).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(getAuthed("/api/v1/lending/" + id, a.accessToken())).andExpect(jsonPath("$.data.outstanding").value(900.00));
    }
}
