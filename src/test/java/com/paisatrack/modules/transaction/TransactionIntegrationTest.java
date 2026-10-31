package com.paisatrack.modules.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.paisatrack.support.FinanceIntegrationTest;

class TransactionIntegrationTest extends FinanceIntegrationTest {

    private static final String DAY = "2026-03-14";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void quickExpenseNeedsOnlyAmountAndCategory() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        postAuthed("/api/v1/expenses", session, body("amount", "150", "categoryId", category(session, "FOOD_LUNCH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.amount").value(150.00))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.account.id").value(cash.toString()))
                .andExpect(jsonPath("$.data.paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.data.transactionDate").value(today.toString()))
                .andExpect(jsonPath("$.data.category.name").value("Lunch"))
                .andExpect(jsonPath("$.data.category.parentName").value("Food & Dining"));

        assertThat(balance(session, cash)).isEqualByComparingTo("-150");
    }

    @Test
    void dailySummaryTotalsOnlyRealIncomeAndExpense() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID lunch = category(session, "FOOD_LUNCH");
        UUID tea = category(session, "FOOD_TEA_COFFEE");
        UUID groceries = category(session, "GROCERIES_VEGETABLES");

        expense(session, "150", lunch, "transactionDate", DAY, "description", "Thali");
        expense(session, "40", tea, "transactionDate", DAY);
        UUID big = expense(session, "600", groceries, "transactionDate", DAY, "description", "Weekly veg");
        income(session, "50000", category(session, "INC_SALARY"), "transactionDate", DAY, "accountId", bank);
        transfer(session, "1000", bank, defaultAccount(session), "transactionDate", DAY);
        UUID voided = expense(session, "999", lunch, "transactionDate", DAY);
        mockMvc.perform(authed(post("/api/v1/transactions/" + voided + "/void"), session.accessToken()))
                .andExpect(status().isOk());
        expense(session, "70", lunch, "transactionDate", "2026-03-15");

        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary?date=" + DAY, session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(DAY))
                .andExpect(jsonPath("$.data.basis").exists())
                .andExpect(jsonPath("$.data.currencies", hasSize(1)))
                .andExpect(jsonPath("$.data.currencies[0].currency").value("INR"))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(790.00))
                .andExpect(jsonPath("$.data.currencies[0].expenseCount").value(3))
                .andExpect(jsonPath("$.data.currencies[0].totalIncome").value(50000.00))
                .andExpect(jsonPath("$.data.currencies[0].incomeCount").value(1))
                .andExpect(jsonPath("$.data.currencies[0].netCashFlow").value(49210.00))
                .andExpect(jsonPath("$.data.currencies[0].averageExpense").value(263.33))
                .andExpect(jsonPath("$.data.currencies[0].highestExpense.transactionId").value(big.toString()))
                .andExpect(jsonPath("$.data.currencies[0].highestExpense.amount").value(600.00))
                .andExpect(jsonPath("$.data.currencies[0].highestExpense.categoryName").value("Groceries / Vegetables"))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory", hasSize(2)))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[0].name").value("Groceries"))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[0].total").value(600.00))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[1].name").value("Food & Dining"))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[1].total").value(190.00))
                .andExpect(jsonPath("$.data.currencies[0].expenseByCategory[1].count").value(2));
    }

    @Test
    void emptyDayReturnsZerosInDefaultCurrency() throws Exception {
        Session session = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary?date=2020-01-01", session.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].currency").value("INR"))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00))
                .andExpect(jsonPath("$.data.currencies[0].highestExpense").doesNotExist());
    }

    @Test
    void editingAnExpenseUpdatesBalanceSummaryAndAuditTrail() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        UUID lunch = category(session, "FOOD_LUNCH");
        UUID id = expense(session, "150", lunch, "transactionDate", DAY);

        putAuthed("/api/v1/expenses/" + id, session, body("amount", "250", "categoryId", lunch, "accountId", cash,
                        "transactionDate", DAY, "merchant", "Cafe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(250.00))
                .andExpect(jsonPath("$.data.counterparty").value("Cafe"));

        assertThat(balance(session, cash)).isEqualByComparingTo("-250");
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(250.00));
        mockMvc.perform(getAuthed("/api/v1/transactions/" + id + "/history", session.accessToken()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].action").value("CREATED"))
                .andExpect(jsonPath("$.data[1].action").value("UPDATED"))
                .andExpect(jsonPath("$.data[1].changes.amount.from").value("150"))
                .andExpect(jsonPath("$.data[1].changes.amount.to").value("250"))
                .andExpect(jsonPath("$.data[1].changes.counterparty.to").value("Cafe"))
                .andExpect(jsonPath("$.data[1].changes.categoryId").doesNotExist());
    }

    @Test
    void movingAnExpenseToAnotherAccountMovesTheBalance() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        UUID bank = createAccount(session, "Bank", "SAVINGS", "1000");
        UUID food = category(session, "FOOD");
        UUID id = expense(session, "100", food, "transactionDate", DAY);

        putAuthed("/api/v1/expenses/" + id, session, body("amount", "100", "categoryId", food, "accountId", bank,
                "transactionDate", DAY)).andExpect(status().isOk());

        assertThat(balance(session, cash)).isEqualByComparingTo("0");
        assertThat(balance(session, bank)).isEqualByComparingTo("900");
    }

    @Test
    void voidAndRestoreAreReflectedEverywhereAndAreRepeatable() throws Exception {
        Session session = register(uniqueEmail());
        UUID cash = defaultAccount(session);
        UUID food = category(session, "FOOD");
        UUID id = expense(session, "300", food, "transactionDate", DAY);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(authed(post("/api/v1/transactions/" + id + "/void"), session.accessToken())
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Duplicate entry"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("VOIDED"))
                    .andExpect(jsonPath("$.data.voidReason").value("Duplicate entry"));
        }
        assertThat(balance(session, cash)).isEqualByComparingTo("0");
        mockMvc.perform(getAuthed("/api/v1/transactions", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(getAuthed("/api/v1/transactions?includeVoided=true", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary?date=" + DAY, session.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00));
        putAuthed("/api/v1/expenses/" + id, session, body("amount", "1", "categoryId", food, "accountId", cash,
                        "transactionDate", DAY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("TRANSACTION_VOIDED"));

        mockMvc.perform(authed(post("/api/v1/transactions/" + id + "/restore"), session.accessToken()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(balance(session, cash)).isEqualByComparingTo("-300");

        List<String> actions = read("/api/v1/transactions/" + id + "/history", session, "$.data[*].action");
        assertThat(actions).containsExactly("CREATED", "VOIDED", "RESTORED");
    }

    @Test
    void invalidAmountsDatesAndCurrenciesAreRejected() throws Exception {
        Session session = register(uniqueEmail());
        UUID food = category(session, "FOOD");
        UUID yen = createAccount(session, Map.of("name", "Yen", "type", "CASH", "currency", "JPY"));
        UUID usd = createAccount(session, Map.of("name", "Dollars", "type", "CASH", "currency", "USD"));

        for (String bad : List.of("0", "-5", "10.555")) {
            postAuthed("/api/v1/expenses", session, body("amount", bad, "categoryId", food))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.fields[0].field").value("amount"));
        }
        postAuthed("/api/v1/expenses", session, body("amount", "10.5", "categoryId", food, "accountId", yen))
                .andExpect(status().isBadRequest());
        postAuthed("/api/v1/expenses", session, body("amount", "10", "categoryId", food, "currency", "USD"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("CURRENCY_MISMATCH"));
        postAuthed("/api/v1/expenses", session, body("amount", "10", "categoryId", food,
                        "transactionDate", LocalDate.now().plusDays(5).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("transactionDate"));
        postAuthed("/api/v1/income", session, body("amount", "10", "categoryId", food))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("categoryId"));
        postAuthed("/api/v1/expenses", session, body("amount", "10", "categoryId", category(session, "INC_SALARY")))
                .andExpect(status().isBadRequest());
        UUID cash = defaultAccount(session);
        postAuthed("/api/v1/transfers", session, body("amount", "10", "fromAccountId", cash, "toAccountId", cash))
                .andExpect(status().isUnprocessableContent());
        postAuthed("/api/v1/transfers", session, body("amount", "10", "fromAccountId", cash, "toAccountId", usd))
                .andExpect(jsonPath("$.error.code").value("CURRENCY_MISMATCH"));
        postAuthed("/api/v1/expenses", session, Map.of("categoryId", food.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("amount"));
    }

    @Test
    void editingRequiresAccountAndDateAndMatchingType() throws Exception {
        Session session = register(uniqueEmail());
        UUID food = category(session, "FOOD");
        UUID id = expense(session, "10", food);

        putAuthed("/api/v1/expenses/" + id, session, body("amount", "20", "categoryId", food))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("accountId"));
        putAuthed("/api/v1/income/" + id, session, body("amount", "20", "categoryId", category(session, "INC_SALARY"),
                        "accountId", defaultAccount(session), "transactionDate", DAY))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotencyKeyPreventsDuplicateExpenses() throws Exception {
        Session session = register(uniqueEmail());
        Map<String, Object> request = body("amount", "150", "categoryId", category(session, "FOOD"));

        String first = postWithKey(session, "expense-key-0001", request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postWithKey(session, "expense-key-0001", request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(second, "$.data.id")).isEqualTo(JsonPath.<String>read(first, "$.data.id"));
        assertThat(countTransactions(session)).isEqualTo(1);

        postWithKey(session, "expense-key-0001", body("amount", "999", "categoryId", category(session, "FOOD")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        postWithKey(session, "short", request).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRetriesWithOneKeyCreateOneExpense() throws Exception {
        Session session = register(uniqueEmail());
        Map<String, Object> request = body("amount", "75", "categoryId", category(session, "FOOD"));
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return postWithKey(session, "concurrent-key-01", request).andReturn().getResponse().getStatus();
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
        assertThat(countTransactions(session)).isEqualTo(1);
    }

    @Test
    void listSupportsFiltersSearchSortingAndPaging() throws Exception {
        Session session = register(uniqueEmail());
        UUID bank = createAccount(session, "Bank", "SAVINGS", "0");
        UUID lunch = category(session, "FOOD_LUNCH");
        UUID dinner = category(session, "FOOD_DINNER");
        UUID fuel = category(session, "TRANSPORT_PETROL");

        expense(session, "100", lunch, "transactionDate", "2026-03-01", "merchant", "Saravana Bhavan");
        expense(session, "300", dinner, "transactionDate", "2026-03-02");
        expense(session, "2000", fuel, "transactionDate", "2026-03-03", "accountId", bank, "description", "Full tank");
        expense(session, "50", lunch, "transactionDate", "2026-03-04", "paymentMethod", "UPI");
        transfer(session, "500", bank, defaultAccount(session), "transactionDate", "2026-03-05");

        mockMvc.perform(getAuthed("/api/v1/transactions?size=2", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].transactionDate").value("2026-03-05"));
        mockMvc.perform(getAuthed("/api/v1/expenses?categoryId=" + category(session, "FOOD"), session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(3));
        mockMvc.perform(getAuthed("/api/v1/transactions?q=saravana", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(getAuthed("/api/v1/transactions?q=TANK", session.accessToken()))
                .andExpect(jsonPath("$.data.items[0].amount").value(2000.00));
        mockMvc.perform(getAuthed("/api/v1/transactions?from=2026-03-02&to=2026-03-03", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(getAuthed("/api/v1/expenses?sortBy=AMOUNT&direction=ASC", session.accessToken()))
                .andExpect(jsonPath("$.data.items[0].amount").value(50.00));
        mockMvc.perform(getAuthed("/api/v1/transactions?paymentMethod=UPI", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(getAuthed("/api/v1/accounts/" + bank + "/transactions", session.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(2));
        mockMvc.perform(getAuthed("/api/v1/transfers", session.accessToken()))
                .andExpect(jsonPath("$.data.items[0].toAccount.name").value("Cash"))
                .andExpect(jsonPath("$.data.items[0].category").doesNotExist());

        mockMvc.perform(getAuthed("/api/v1/transactions?from=2026-03-05&to=2026-03-01", session.accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(getAuthed("/api/v1/transactions?accountId=not-a-uuid", session.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].message").value("has an invalid value"));
        mockMvc.perform(getAuthed("/api/v1/transactions?size=500", session.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transactionsAreIsolatedBetweenUsers() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        UUID food = category(a, "FOOD");
        UUID id = expense(a, "500", food, "transactionDate", DAY);

        mockMvc.perform(getAuthed("/api/v1/transactions/" + id, b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/transactions/" + id + "/history", b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(authed(post("/api/v1/transactions/" + id + "/void"), b.accessToken())).andExpect(status().isNotFound());
        putAuthed("/api/v1/expenses/" + id, b, body("amount", "1", "categoryId", category(b, "FOOD"),
                "accountId", defaultAccount(b), "transactionDate", DAY)).andExpect(status().isNotFound());

        mockMvc.perform(getAuthed("/api/v1/transactions", b.accessToken()))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary?date=" + DAY, b.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00));
        mockMvc.perform(getAuthed("/api/v1/transactions/" + id, a.accessToken()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void changingDefaultCurrencyNeverRewritesHistory() throws Exception {
        Session session = register(uniqueEmail());
        UUID id = expense(session, "150", category(session, "FOOD"));

        patchAuthed("/api/v1/users/me/preferences", session, Map.of("defaultCurrency", "USD")).andExpect(status().isOk());

        mockMvc.perform(getAuthed("/api/v1/transactions/" + id, session.accessToken()))
                .andExpect(jsonPath("$.data.amount").value(150.00))
                .andExpect(jsonPath("$.data.currency").value("INR"));
        mockMvc.perform(getAuthed("/api/v1/accounts/" + defaultAccount(session), session.accessToken()))
                .andExpect(jsonPath("$.data.currency").value("INR"));
        // New accounts pick up the new preference.
        mockMvc.perform(authed(post("/api/v1/accounts"), session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "US", "type", "SAVINGS"))))
                .andExpect(jsonPath("$.data.currency").value("USD"));
    }

    private org.springframework.test.web.servlet.ResultActions postWithKey(Session session, String key, Object body)
            throws Exception {
        return mockMvc.perform(authed(post("/api/v1/expenses"), session.accessToken())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private int countTransactions(Session session) {
        Integer count = jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, session.userId());
        return count == null ? 0 : count;
    }
}
