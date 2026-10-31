package com.paisatrack.modules.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.ResultActions;

import com.paisatrack.support.FinanceIntegrationTest;

class LoanIntegrationTest extends FinanceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    /** ₹5,00,000 at 10.5% for 60 months: EMI ₹10,746.95, first month interest ₹4,375.00. */
    private UUID loan(Session s, Object... extra) throws Exception {
        Map<String, Object> b = body("name", "Car loan", "lender", "HDFC Bank", "type", "VEHICLE", "principal", "500000",
                "annualRate", "10.5", "tenureMonths", 60);
        b.putAll(body(extra));
        return createdId(postAuthed("/api/v1/loans", s, b));
    }

    private ResultActions payEmi(Session s, UUID loanId, Object... extra) throws Exception {
        return postAuthed("/api/v1/loans/" + loanId + "/emi-payments", s, body(extra));
    }

    @Test
    void emiAndScheduleFollowTheStandardFormula() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = loan(s);

        mockMvc.perform(getAuthed("/api/v1/loans/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.emiAmount").value(10746.95))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(500000.00))
                .andExpect(jsonPath("$.data.installmentsRemaining").value(60))
                .andExpect(jsonPath("$.data.interestCategory.name").value("Interest"))
                .andExpect(jsonPath("$.data.nextInstallment.number").value(1))
                .andExpect(jsonPath("$.data.nextInstallment.interestAmount").value(4375.00))
                .andExpect(jsonPath("$.data.nextInstallment.principalAmount").value(6371.95))
                .andExpect(jsonPath("$.data.firstEmiDate").value(TODAY.plusMonths(1).toString()));
        List<Double> principals = read("/api/v1/loans/" + id + "/schedule", s, "$.data[*].principalAmount");
        assertThat(principals).hasSize(60);
        assertThat(principals.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("500000");

        postAuthed("/api/v1/loans/preview", s, body("principal", "1000000", "annualRate", "9", "tenureMonths", 240))
                .andExpect(jsonPath("$.data.emiAmount").value(8997.26))
                .andExpect(jsonPath("$.data.installments").value(240));
    }

    @Test
    void disbursementRaisesTheBalanceButIsNotIncome() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "0");
        loan(s, "disbursementAccountId", bank);

        assertThat(balance(s, bank)).isEqualByComparingTo("500000");
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalIncome").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/transactions", s.accessToken()))
                .andExpect(jsonPath("$.data.items[0].type").value("LOAN_DISBURSEMENT"));
        // Loan principal is a liability: borrowing doesn't make you richer.
        mockMvc.perform(getAuthed("/api/v1/accounts/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalAssets").value(500000.00))
                .andExpect(jsonPath("$.data.currencies[0].loanOutstanding").value(500000.00))
                .andExpect(jsonPath("$.data.currencies[0].netWorth").value(0.00));
    }

    @Test
    void emiPaymentSplitsInterestAsSpendingAndPrincipalAsRepayment() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "0");
        UUID id = loan(s, "disbursementAccountId", bank, "paymentAccountId", bank,
                "startDate", TODAY.minusDays(40).toString(), "firstEmiDate", TODAY.minusDays(10).toString());

        mockMvc.perform(getAuthed("/api/v1/loans/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.missedCount").value(1))
                .andExpect(jsonPath("$.data.nextInstallment.status").value("MISSED"));

        payEmi(s, id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.principalPaid").value(6371.95))
                .andExpect(jsonPath("$.data.interestPaid").value(4375.00))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(493628.05))
                .andExpect(jsonPath("$.data.missedCount").value(0))
                .andExpect(jsonPath("$.data.payments[0].inLedger").value(true));

        assertThat(balance(s, bank)).isEqualByComparingTo("489253.05");
        // Only the interest is spending; principal is not counted again.
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(4375.00))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[0].name").value("Financial Charges"));
        mockMvc.perform(getAuthed("/api/v1/dashboard", s.accessToken()))
                .andExpect(jsonPath("$.data.periods.month.expense").value(4375.00))
                .andExpect(jsonPath("$.data.accounts.loanOutstanding").value(493628.05))
                .andExpect(jsonPath("$.data.accounts.netWorth").value(-4375.00))
                .andExpect(jsonPath("$.data.loans.monthlyEmi").value(10746.95));
        List<String> types = read("/api/v1/transactions?type=LOAN_PRINCIPAL", s, "$.data.items[*].type");
        assertThat(types).containsExactly("LOAN_PRINCIPAL");
    }

    @Test
    void emisArePaidInOrder() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = loan(s);
        payEmi(s, id, "installmentNumber", 3).andExpect(status().isUnprocessableContent());
        payEmi(s, id, "installmentNumber", 1).andExpect(status().isCreated());
        payEmi(s, id, "installmentNumber", 1).andExpect(status().isUnprocessableContent());
        payEmi(s, id).andExpect(jsonPath("$.data.installmentsPaid").value(2));
    }

    @Test
    void prepaymentKeepsTheEmiAndShortensTheLoan() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = loan(s);
        payEmi(s, id).andExpect(status().isCreated());

        String json = postAuthed("/api/v1/loans/" + id + "/prepayments", s, body("amount", "200000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emiAmount").value(10746.95))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(293628.05))
                .andReturn().getResponse().getContentAsString();
        int remaining = JsonPath.read(json, "$.data.installmentsRemaining");
        assertThat(remaining).isLessThan(59);

        List<Double> unpaid = read("/api/v1/loans/" + id + "/schedule", s, "$.data[?(@.status != 'PAID')].principalAmount");
        assertThat(unpaid.stream().map(BigDecimal::valueOf).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("293628.05");
        List<Integer> numbers = read("/api/v1/loans/" + id + "/schedule", s, "$.data[*].number");
        assertThat(numbers.get(1)).isEqualTo(2);

        postAuthed("/api/v1/loans/" + id + "/prepayments", s, body("amount", "300000"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("REPAYMENT_EXCEEDS_OUTSTANDING"));
        postAuthed("/api/v1/loans/" + id + "/prepayments", s, body("amount", "293628.05"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(0.00))
                .andExpect(jsonPath("$.data.installmentsRemaining").value(0));
        payEmi(s, id).andExpect(jsonPath("$.error.code").value("LOAN_CLOSED"));
    }

    @Test
    void onlyTheLatestPaymentCanBeVoidedAndVoidingRestoresEverything() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "100000");
        UUID id = loan(s, "paymentAccountId", bank);
        String first = JsonPath.read(payEmi(s, id).andReturn().getResponse().getContentAsString(), "$.data.payments[0].id");
        String prepay = postAuthed("/api/v1/loans/" + id + "/prepayments", s, body("amount", "50000")).andReturn().getResponse()
                .getContentAsString();
        String prepayId = JsonPath.read(prepay, "$.data.payments[1].id");
        int shortened = JsonPath.read(prepay, "$.data.installmentsRemaining");

        mockMvc.perform(authed(post("/api/v1/loans/" + id + "/payments/" + first + "/void"), s.accessToken()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(authed(post("/api/v1/loans/" + id + "/payments/" + prepayId + "/void"), s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(493628.05))
                .andExpect(jsonPath("$.data.installmentsRemaining").value(59));
        assertThat(shortened).isLessThan(59);
        mockMvc.perform(authed(post("/api/v1/loans/" + id + "/payments/" + first + "/void"), s.accessToken()))
                .andExpect(jsonPath("$.data.outstandingPrincipal").value(500000.00))
                .andExpect(jsonPath("$.data.payments[0].voided").value(true));
        assertThat(balance(s, bank)).isEqualByComparingTo("100000");
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00));
    }

    @Test
    void loanEntriesCanOnlyBeChangedFromTheLoan() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = loan(s);
        payEmi(s, id).andExpect(status().isCreated());
        String interestTx = read("/api/v1/expenses", s, "$.data.items[0].id");

        mockMvc.perform(authed(post("/api/v1/transactions/" + interestTx + "/void"), s.accessToken()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("LOAN_MANAGED"));
        putAuthed("/api/v1/expenses/" + interestTx, s, body("amount", "1", "categoryId", category(s, "FOOD"),
                "accountId", defaultAccount(s), "transactionDate", TODAY.toString()))
                .andExpect(jsonPath("$.error.code").value("LOAN_MANAGED"));
        mockMvc.perform(authed(delete("/api/v1/categories/" + category(s, "FINANCE_INTEREST")), s.accessToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void existingLoansCanStartWithEmisPaidBeforeTracking() throws Exception {
        Session s = register(uniqueEmail());
        // Installment 13 fell due ~20 days ago; 14 is next month (stable even at month end).
        LocalDate firstEmi = TODAY.minusDays(20).minusMonths(12);
        UUID id = loan(s, "startDate", firstEmi.minusMonths(1).toString(), "firstEmiDate", firstEmi.toString(),
                "emisPaidBeforeTracking", 12);

        mockMvc.perform(getAuthed("/api/v1/loans/" + id, s.accessToken()))
                .andExpect(jsonPath("$.data.installmentsPaid").value(12))
                .andExpect(jsonPath("$.data.payments", hasSize(12)))
                .andExpect(jsonPath("$.data.payments[0].inLedger").value(false))
                .andExpect(jsonPath("$.data.missedCount").value(1))
                .andExpect(jsonPath("$.data.nextInstallment.number").value(13));
        mockMvc.perform(getAuthed("/api/v1/transactions", s.accessToken())).andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(getAuthed("/api/v1/emis/upcoming", s.accessToken()))
                .andExpect(jsonPath("$.data[0].number").value(13))
                .andExpect(jsonPath("$.data[0].status").value("MISSED"));
        postAuthed("/api/v1/loans", s, body("name", "Bad", "type", "PERSONAL", "principal", "1000", "annualRate", "10",
                        "tenureMonths", 12, "emisPaidBeforeTracking", 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("emisPaidBeforeTracking"));
    }

    @Test
    void termsChangeOnlyBeforePaymentsAndDeletionNeedsNoPayments() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "0");
        UUID id = loan(s, "disbursementAccountId", bank);

        patchAuthed("/api/v1/loans/" + id, s, body("annualRate", "9", "principal", "400000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emiAmount").value(8303.34));
        assertThat(balance(s, bank)).isEqualByComparingTo("400000");
        payEmi(s, id, "accountId", bank).andExpect(status().isCreated());
        patchAuthed("/api/v1/loans/" + id, s, body("annualRate", "8"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LOAN_HAS_PAYMENTS"));
        patchAuthed("/api/v1/loans/" + id, s, Map.of("name", "Swift loan")).andExpect(jsonPath("$.data.name").value("Swift loan"));
        mockMvc.perform(authed(delete("/api/v1/loans/" + id), s.accessToken())).andExpect(status().isConflict());

        UUID other = loan(s, "disbursementAccountId", bank);
        mockMvc.perform(authed(delete("/api/v1/loans/" + other), s.accessToken())).andExpect(status().isNoContent());
        mockMvc.perform(getAuthed("/api/v1/loans/" + other, s.accessToken())).andExpect(status().isNotFound());
        assertThat(balance(s, bank)).isEqualByComparingTo(new BigDecimal("400000").subtract(new BigDecimal("8303.34")));
    }

    @Test
    void parallelEmiPaymentsNeverPayTheSameInstallmentTwice() throws Exception {
        Session s = register(uniqueEmail());
        UUID id = loan(s);
        int attempts = 4;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return payEmi(s, id).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        List<Integer> paid = read("/api/v1/loans/" + id + "/schedule", s, "$.data[?(@.status == 'PAID')].number");
        assertThat(paid).containsExactly(1, 2, 3, 4);
    }

    @Test
    void summaryAndUpcomingEmis() throws Exception {
        Session s = register(uniqueEmail());
        loan(s, "firstEmiDate", TODAY.plusDays(5).toString());
        loan(s, "name", "Home loan", "type", "HOME", "principal", "2500000", "annualRate", "8.5", "tenureMonths", 240,
                "firstEmiDate", TODAY.plusDays(20).toString());

        mockMvc.perform(getAuthed("/api/v1/loans/summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].activeLoans").value(2))
                .andExpect(jsonPath("$.data.currencies[0].outstandingPrincipal").value(3000000.00))
                .andExpect(jsonPath("$.data.upcoming", hasSize(2)))
                .andExpect(jsonPath("$.data.upcoming[0].loanName").value("Car loan"));
        mockMvc.perform(getAuthed("/api/v1/emis/upcoming?days=10", s.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }

    @Test
    void loansAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID id = loan(a);

        mockMvc.perform(getAuthed("/api/v1/loans/" + id, b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/loans/" + id + "/schedule", b.accessToken())).andExpect(status().isNotFound());
        payEmi(b, id).andExpect(status().isNotFound());
        postAuthed("/api/v1/loans/" + id + "/prepayments", b, body("amount", "1")).andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/loans/" + id), b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/loans", b.accessToken())).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(getAuthed("/api/v1/accounts/summary", b.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].loanOutstanding").value(0.00));
    }
}
