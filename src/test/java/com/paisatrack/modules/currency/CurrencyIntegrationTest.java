package com.paisatrack.modules.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.paisatrack.support.FinanceIntegrationTest;

class CurrencyIntegrationTest extends FinanceIntegrationTest {

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private static BigDecimal num(String json, String path) {
        Object v = JsonPath.read(json, path);
        return new BigDecimal(v.toString());
    }

    private String json(String url, Session s) throws Exception {
        return mockMvc.perform(getAuthed(url, s.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private UUID rate(Session s, String base, String quote, String rate, LocalDate date) throws Exception {
        return createdId(postAuthed("/api/v1/exchange-rates", s, body("base", base, "quote", quote, "rate", rate, "date", date.toString())));
    }

    @Test
    void currenciesListIsoCodesWithPrecision() throws Exception {
        Session s = register(uniqueEmail());
        String r = json("/api/v1/currencies", s);
        assertThat((List<String>) JsonPath.read(r, "$.data[?(@.code == 'INR')].symbol")).containsExactly("₹");
        assertThat((List<Integer>) JsonPath.read(r, "$.data[?(@.code == 'INR')].fractionDigits")).containsExactly(2);
        assertThat((List<Integer>) JsonPath.read(r, "$.data[?(@.code == 'JPY')].fractionDigits")).containsExactly(0);
        assertThat((List<Integer>) JsonPath.read(r, "$.data[?(@.code == 'KWD')].fractionDigits")).containsExactly(3);
        mockMvc.perform(getAuthed("/api/v1/users/me/preferences", s.accessToken())).andExpect(jsonPath("$.data.defaultCurrency").value("INR"));
    }

    @Test
    void conversionUsesTheLatestRateOnOrBeforeTheDateAndKeepsTheOriginal() throws Exception {
        Session s = register(uniqueEmail());
        LocalDate d1 = today().minusDays(20);
        rate(s, "USD", "INR", "83", d1);
        rate(s, "USD", "INR", "84.5", d1.plusDays(10));

        String early = json("/api/v1/exchange-rates/convert?amount=100&from=USD&to=INR&date=" + d1.plusDays(3), s);
        assertThat(num(early, "$.data.convertedAmount")).isEqualByComparingTo("8300");
        assertThat(num(early, "$.data.rate")).isEqualByComparingTo("83");
        assertThat((String) JsonPath.read(early, "$.data.rateDate")).isEqualTo(d1.toString());
        assertThat((String) JsonPath.read(early, "$.data.source")).isEqualTo("MANUAL");
        assertThat(num(early, "$.data.originalAmount")).isEqualByComparingTo("100");
        assertThat((String) JsonPath.read(early, "$.data.originalCurrency")).isEqualTo("USD");
        assertThat((String) JsonPath.read(early, "$.data.reportingCurrency")).isEqualTo("INR");

        String later = json("/api/v1/exchange-rates/convert?amount=100&from=USD&to=INR", s);
        assertThat(num(later, "$.data.convertedAmount")).isEqualByComparingTo("8450");

        // The inverse pair works too, rounded to the target currency.
        String inverse = json("/api/v1/exchange-rates/convert?amount=1000&from=INR&to=USD&date=" + d1, s);
        assertThat(num(inverse, "$.data.convertedAmount")).isEqualByComparingTo("12.05");

        String before = json("/api/v1/exchange-rates/convert?amount=100&from=USD&to=INR&date=" + d1.minusDays(1), s);
        assertThat((boolean) JsonPath.read(before, "$.data.available")).isFalse();
        assertThat((String) JsonPath.read(before, "$.data.message")).contains("No USD to INR rate on or before");
        assertThat((Object) JsonPath.read(before, "$.data.convertedAmount")).isNull();
    }

    @Test
    void ratesAreValidatedReplacedAndPrivate() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        LocalDate d = today();
        UUID id = rate(a, "EUR", "INR", "90", d);
        rate(a, "EUR", "INR", "91", d);
        assertThat((List<?>) JsonPath.read(json("/api/v1/exchange-rates?currency=EUR", a), "$.data")).hasSize(1);
        assertThat(num(json("/api/v1/exchange-rates/convert?amount=1&from=EUR&to=INR", a), "$.data.convertedAmount")).isEqualByComparingTo("91");

        postAuthed("/api/v1/exchange-rates", a, body("base", "EUR", "quote", "EUR", "rate", "1")).andExpect(status().isBadRequest());
        postAuthed("/api/v1/exchange-rates", a, body("base", "EUR", "quote", "INR", "rate", "0")).andExpect(status().isBadRequest());
        postAuthed("/api/v1/exchange-rates", a, body("base", "EUR", "quote", "INR", "rate", "90", "date", d.plusDays(3).toString()))
                .andExpect(status().isBadRequest());
        postAuthed("/api/v1/exchange-rates", a, body("base", "XYZ", "quote", "INR", "rate", "90")).andExpect(status().isBadRequest());

        assertThat((List<?>) JsonPath.read(json("/api/v1/exchange-rates", b), "$.data")).isEmpty();
        assertThat((boolean) JsonPath.read(json("/api/v1/exchange-rates/convert?amount=1&from=EUR&to=INR", b), "$.data.available")).isFalse();
        mockMvc.perform(authed(delete("/api/v1/exchange-rates/" + id), b.accessToken())).andExpect(status().isNotFound());
        mockMvc.perform(authed(delete("/api/v1/exchange-rates/" + id), a.accessToken())).andExpect(status().isNoContent());
        mockMvc.perform(authed(post("/api/v1/exchange-rates/refresh"), a.accessToken()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void reportsConvertEachEntryAtItsOwnDateAndListWhatHasNoRate() throws Exception {
        Session s = register(uniqueEmail());
        YearMonth m = YearMonth.from(today()).minusMonths(1);
        UUID usd = createAccount(s, body("name", "USD card", "type", "WALLET", "currency", "USD", "openingBalance", "0"));
        UUID eur = createAccount(s, body("name", "EUR cash", "type", "CASH", "currency", "EUR", "openingBalance", "0"));
        rate(s, "USD", "INR", "80", m.atDay(1));
        rate(s, "USD", "INR", "85", m.atDay(15));
        expense(s, "1000", category(s, "FOOD_LUNCH"), "transactionDate", m.atDay(5).toString());
        expense(s, "10", category(s, "FOOD_LUNCH"), "accountId", usd, "transactionDate", m.atDay(10).toString());
        expense(s, "10", category(s, "SHOPPING"), "accountId", usd, "transactionDate", m.atDay(20).toString());
        expense(s, "7", category(s, "FOOD_LUNCH"), "accountId", eur, "transactionDate", m.atDay(20).toString());

        String r = json("/api/v1/reports/monthly?month=" + m, s);
        // 1000 + 10 x 80 + 10 x 85; the EUR entry has no rate and is listed, never added raw.
        assertThat(num(r, "$.data.summary.expense")).isEqualByComparingTo("2650");
        assertThat((int) JsonPath.read(r, "$.data.unconvertedEntries")).isEqualTo(1);
        assertThat((List<Integer>) JsonPath.read(r, "$.data.conversions[?(@.currency == 'USD')].convertedEntries")).containsExactly(2);
        assertThat((List<String>) JsonPath.read(r, "$.data.conversions[?(@.currency == 'USD')].firstRateDate")).containsExactly(m.atDay(1).toString());
        assertThat((List<String>) JsonPath.read(r, "$.data.conversions[?(@.currency == 'USD')].lastRateDate")).containsExactly(m.atDay(15).toString());
        assertThat((List<String>) JsonPath.read(r, "$.data.conversions[?(@.currency == 'EUR')].missingFrom")).containsExactly(m.atDay(20).toString());
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[?(@.code == 'UNCONVERTED')].message")).hasSize(1);
        BigDecimal byCategory = ((List<Object>) JsonPath.read(r, "$.data.expenseByCategory[*].total")).stream()
                .map(v -> new BigDecimal(v.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(byCategory).isEqualByComparingTo("2650");
        assertThat((List<String>) JsonPath.read(r, "$.data.topExpenses[?(@.originalCurrency == 'USD')].originalCurrency")).hasSize(2);
        // Accounts are never converted: each line keeps its own currency.
        assertThat((List<String>) JsonPath.read(r, "$.data.accounts[?(@.name == 'USD card')].currency")).containsExactly("USD");

        // The same report in USD converts the INR entry instead.
        String inUsd = json("/api/v1/reports/monthly?currency=USD&month=" + m, s);
        assertThat(num(inUsd, "$.data.summary.expense")).isEqualByComparingTo("32.50");
    }

    @Test
    void changingTheDefaultCurrencyNeverRewritesHistory() throws Exception {
        Session s = register(uniqueEmail());
        UUID expense = expense(s, "830", category(s, "FOOD_LUNCH"));
        rate(s, "USD", "INR", "83", today());
        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), s.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("defaultCurrency", "USD")))).andExpect(status().isOk());

        mockMvc.perform(getAuthed("/api/v1/transactions/" + expense, s.accessToken()))
                .andExpect(jsonPath("$.data.amount").value(830.00))
                .andExpect(jsonPath("$.data.currency").value("INR"));
        String d = json("/api/v1/dashboard", s);
        assertThat((String) JsonPath.read(d, "$.data.currency")).isEqualTo("USD");
        assertThat(num(d, "$.data.periods.today.expense")).isEqualByComparingTo("10");
        assertThat((List<?>) JsonPath.read(d, "$.data.accounts.unconvertedCurrencies")).isEmpty();
    }

    @Test
    void dashboardConvertsOtherCurrenciesAndNetWorth() throws Exception {
        Session s = register(uniqueEmail());
        UUID usd = createAccount(s, body("name", "USD wallet", "type", "WALLET", "currency", "USD", "openingBalance", "100"));
        expense(s, "500", category(s, "FOOD_LUNCH"));
        expense(s, "5", category(s, "FOOD_LUNCH"), "accountId", usd);

        String before = json("/api/v1/dashboard", s);
        assertThat(num(before, "$.data.periods.today.expense")).isEqualByComparingTo("500");
        assertThat((int) JsonPath.read(before, "$.data.unconvertedTransactions")).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(before, "$.data.accounts.unconvertedCurrencies")).containsExactly("USD");

        rate(s, "USD", "INR", "80", today());
        String after = json("/api/v1/dashboard", s);
        assertThat(num(after, "$.data.periods.today.expense")).isEqualByComparingTo("900");
        assertThat((int) JsonPath.read(after, "$.data.unconvertedTransactions")).isZero();
        // Cash -500 (INR) + 95 USD x 80.
        assertThat(num(after, "$.data.accounts.netWorth")).isEqualByComparingTo("7100");
        assertThat((List<Double>) JsonPath.read(after, "$.data.accounts.accounts[?(@.name == 'USD wallet')].convertedBalance"))
                .containsExactly(7600.0);
        assertThat(num(after, "$.data.expenseByCategory[0].total")).isEqualByComparingTo("900");
    }

    @Test
    void transfersAcrossCurrenciesRecordBothSides() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "100000");
        UUID usd = createAccount(s, body("name", "USD wallet", "type", "WALLET", "currency", "USD", "openingBalance", "0"));
        UUID jpy = createAccount(s, body("name", "Yen", "type", "CASH", "currency", "JPY", "openingBalance", "0"));

        String t = postAuthed("/api/v1/transfers", s, body("amount", "8350", "fromAccountId", bank, "toAccountId", usd, "toAmount", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.toAmount").value(100.00))
                .andExpect(jsonPath("$.data.toCurrency").value("USD"))
                .andReturn().getResponse().getContentAsString();
        assertThat(balance(s, bank)).isEqualByComparingTo("91650");
        assertThat(balance(s, usd)).isEqualByComparingTo("100");
        mockMvc.perform(getAuthed("/api/v1/transactions/daily-summary", s.accessToken()))
                .andExpect(jsonPath("$.data.currencies[0].totalExpense").value(0.00))
                .andExpect(jsonPath("$.data.currencies[0].totalIncome").value(0.00));

        String id = JsonPath.read(t, "$.data.id");
        putAuthed("/api/v1/transfers/" + id, s, body("amount", "8400", "fromAccountId", bank, "toAccountId", usd, "toAmount", "100.50",
                "transactionDate", today().toString())).andExpect(status().isOk());
        assertThat(balance(s, usd)).isEqualByComparingTo("100.50");
        mockMvc.perform(getAuthed("/api/v1/transactions/" + id + "/history", s.accessToken()))
                .andExpect(jsonPath("$.data[-1].changes.toAmount.to").value("100.5"));

        postAuthed("/api/v1/transfers", s, body("amount", "1000", "fromAccountId", bank, "toAccountId", jpy, "toAmount", "1700.5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("toAmount"));
        // Same currency: a stray toAmount is ignored, never stored.
        UUID cash = defaultAccount(s);
        postAuthed("/api/v1/transfers", s, body("amount", "500", "fromAccountId", bank, "toAccountId", cash, "toAmount", "999"))
                .andExpect(jsonPath("$.data.toAmount").doesNotExist());
        assertThat(balance(s, cash)).isEqualByComparingTo("500");
    }
}
