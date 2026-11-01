package com.paisatrack.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.paisatrack.support.FinanceIntegrationTest;

class ReportIntegrationTest extends FinanceIntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    private static BigDecimal sum(List<?> values) {
        return values.stream().map(v -> new BigDecimal(v.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal num(String json, String path) {
        Object v = JsonPath.read(json, path);
        if (v instanceof List<?> list) {
            assertThat(list).as(path).hasSize(1);
            v = list.getFirst();
        }
        return new BigDecimal(v.toString());
    }

    private String report(Session s, String url) throws Exception {
        return mockMvc.perform(getAuthed("/api/v1/reports/" + url, s.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void monthlyReportKeepsEveryKindOfMovementApartAndReconciles() throws Exception {
        Session s = register(uniqueEmail());
        YearMonth m = YearMonth.from(today()).minusMonths(1);
        UUID bank = createAccount(s, "Bank", "SAVINGS", "50000");
        UUID card = createAccount(s, "Card", "CREDIT_CARD", "0");
        UUID funds = createAccount(s, "Mutual funds", "INVESTMENT", "0");
        UUID usd = createAccount(s, body("name", "USD wallet", "type", "WALLET", "currency", "USD", "openingBalance", "100"));
        UUID cash = defaultAccount(s);
        UUID salary = createdId(postAuthed("/api/v1/income-sources", s, body("name", "Acme salary", "type", "SALARY", "accountId", bank)));

        income(s, "90000", null, "incomeSourceId", salary, "accountId", bank, "transactionDate", m.atDay(1).toString());
        income(s, "10000", category(s, "INC_FREELANCE"), "accountId", bank, "transactionDate", m.atDay(6).toString());
        expense(s, "3000", category(s, "FOOD_LUNCH"), "accountId", bank, "transactionDate", m.atDay(5).toString());
        expense(s, "5000", category(s, "GROCERIES_VEGETABLES"), "accountId", bank, "transactionDate", m.atDay(5).toString());
        expense(s, "4000", category(s, "SHOPPING"), "accountId", card, "transactionDate", m.atDay(8).toString());
        UUID voided = expense(s, "999", category(s, "SHOPPING"), "accountId", bank, "transactionDate", m.atDay(9).toString());
        mockMvc.perform(authed(post("/api/v1/transactions/" + voided + "/void"), s.accessToken())).andExpect(status().isOk());
        expense(s, "10", category(s, "FOOD_LUNCH"), "accountId", usd, "transactionDate", m.atDay(9).toString());
        transfer(s, "4000", bank, card, "transactionDate", m.atDay(12).toString());
        transfer(s, "15000", bank, funds, "transactionDate", m.atDay(12).toString());
        transfer(s, "2000", funds, bank, "transactionDate", m.atDay(25).toString());
        transfer(s, "1000", bank, cash, "transactionDate", m.atDay(14).toString());
        String loan = postAuthed("/api/v1/loans", s, body("name", "Car loan", "type", "VEHICLE", "principal", "100000",
                "annualRate", "12", "tenureMonths", 12, "startDate", m.atDay(2).toString(), "firstEmiDate", m.atDay(20).toString(),
                "disbursementAccountId", bank, "paymentAccountId", bank)).andReturn().getResponse().getContentAsString();
        String loanId = JsonPath.read(loan, "$.data.id");
        String paid = postAuthed("/api/v1/loans/" + loanId + "/emi-payments", s, body("paymentDate", m.atDay(20).toString()))
                .andReturn().getResponse().getContentAsString();
        BigDecimal interest = num(paid, "$.data.payments[0].interestAmount");
        BigDecimal principal = num(paid, "$.data.payments[0].principalAmount");
        String lent = JsonPath.read(postAuthed("/api/v1/lending", s, body("personName", "Rahul", "amount", "2000",
                "startDate", m.atDay(3).toString())).andReturn().getResponse().getContentAsString(), "$.data.id");
        postAuthed("/api/v1/lending/" + lent + "/repayments", s, body("amount", "500", "repaymentDate", m.atDay(15).toString()))
                .andExpect(status().isCreated());
        postAuthed("/api/v1/borrowing", s, body("personName", "Priya", "amount", "1000", "startDate", m.atDay(4).toString()))
                .andExpect(status().isCreated());

        String r = report(s, "monthly?month=" + m);
        BigDecimal expense = new BigDecimal("12000").add(interest);
        assertThat(num(r, "$.data.summary.income")).isEqualByComparingTo("100000");
        assertThat(num(r, "$.data.summary.expense")).isEqualByComparingTo(expense);
        assertThat(num(r, "$.data.summary.interest")).isEqualByComparingTo(interest);
        assertThat(num(r, "$.data.summary.consumption")).isEqualByComparingTo("12000");
        assertThat(num(r, "$.data.summary.netCashFlow")).isEqualByComparingTo(new BigDecimal("100000").subtract(expense));
        assertThat(num(r, "$.data.summary.transfers")).isEqualByComparingTo("22000");
        assertThat((int) JsonPath.read(r, "$.data.summary.transferCount")).isEqualTo(4);
        assertThat(num(r, "$.data.summary.cardBillPayments")).isEqualByComparingTo("4000");
        assertThat(num(r, "$.data.summary.invested")).isEqualByComparingTo("15000");
        assertThat(num(r, "$.data.summary.withdrawn")).isEqualByComparingTo("2000");
        assertThat(num(r, "$.data.summary.netInvested")).isEqualByComparingTo("13000");
        assertThat(num(r, "$.data.summary.loanPrincipalRepaid")).isEqualByComparingTo(principal);
        assertThat(num(r, "$.data.summary.loanDisbursed")).isEqualByComparingTo("100000");
        assertThat(num(r, "$.data.salary.received")).isEqualByComparingTo("90000");
        assertThat(num(r, "$.data.salary.utilization"))
                .isEqualByComparingTo(expense.multiply(BigDecimal.valueOf(100)).divide(new BigDecimal("90000"), 1, RoundingMode.HALF_UP));
        assertThat(num(r, "$.data.lending.lent")).isEqualByComparingTo("2000");
        assertThat(num(r, "$.data.lending.repaidToYou")).isEqualByComparingTo("500");
        assertThat(num(r, "$.data.lending.owedToYouNow")).isEqualByComparingTo("1500");
        assertThat(num(r, "$.data.lending.borrowed")).isEqualByComparingTo("1000");
        assertThat(num(r, "$.data.loans.interestPaid")).isEqualByComparingTo(interest);
        assertThat((boolean) JsonPath.read(r, "$.data.complete")).isTrue();
        assertThat((int) JsonPath.read(r, "$.data.unconvertedEntries")).isEqualTo(1);
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[*].code")).contains("UNCONVERTED");

        // Every breakdown adds back up to the totals.
        assertThat(sum(JsonPath.read(r, "$.data.expenseByCategory[*].total"))).isEqualByComparingTo(expense);
        assertThat(sum(JsonPath.read(r, "$.data.incomeByCategory[*].total"))).isEqualByComparingTo("100000");
        assertThat(sum(JsonPath.read(r, "$.data.series[*].expense"))).isEqualByComparingTo(expense);
        assertThat(sum(JsonPath.read(r, "$.data.series[*].income"))).isEqualByComparingTo("100000");
        assertThat((List<?>) JsonPath.read(r, "$.data.series")).hasSize(m.lengthOfMonth());
        // ...and with the transaction list itself.
        String listed = getJson("/api/v1/transactions?type=EXPENSE&size=100&from=" + m.atDay(1) + "&to=" + m.atEndOfMonth(), s);
        List<Object> inr = JsonPath.read(listed, "$.data.items[?(@.currency == 'INR')].amount");
        assertThat(sum(inr)).isEqualByComparingTo(expense);
        // A credit card bill payment isn't counted again: the card shows the purchase out and the payment in.
        assertThat(num(r, "$.data.accounts[?(@.name == 'Card')].inflow"))
                .isEqualByComparingTo("4000");
        assertThat(num(r, "$.data.accounts[?(@.name == 'Card')].outflow")).isEqualByComparingTo("4000");
        assertThat(num(r, "$.data.accounts[?(@.name == 'Mutual funds')].net")).isEqualByComparingTo("13000");
        assertThat(num(r, "$.data.topExpenses[0].amount")).isEqualByComparingTo("5000");
    }

    @Test
    void runningMonthIsComparedLikeForLike() throws Exception {
        Session s = register(uniqueEmail());
        LocalDate today = today();
        YearMonth prev = YearMonth.from(today).minusMonths(1);
        int day = Math.min(today.getDayOfMonth(), prev.lengthOfMonth());
        expense(s, "700", category(s, "FOOD_LUNCH"), "transactionDate", prev.atDay(day).toString());
        boolean later = day < prev.lengthOfMonth();
        if (later) {
            expense(s, "5000", category(s, "FOOD_LUNCH"), "transactionDate", prev.atDay(day + 1).toString());
        }
        expense(s, "1400", category(s, "FOOD_LUNCH"), "transactionDate", today.toString());

        String r = report(s, "monthly");
        assertThat((boolean) JsonPath.read(r, "$.data.complete")).isFalse();
        assertThat((String) JsonPath.read(r, "$.data.comparison.from")).isEqualTo(prev.atDay(1).toString());
        assertThat((String) JsonPath.read(r, "$.data.comparison.to")).isEqualTo(prev.atDay(day).toString());
        assertThat((String) JsonPath.read(r, "$.data.comparison.basis")).isEqualTo("SAME_POINT");
        assertThat(num(r, "$.data.comparison.expense")).isEqualByComparingTo("700");
        assertThat(num(r, "$.data.comparison.expenseChange")).isEqualByComparingTo("100.0");
        assertThat((List<?>) JsonPath.read(r, "$.data.series")).hasSize(today.getDayOfMonth());
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[*].code")).contains("SPENDING_CHANGE");
    }

    @Test
    void weeksFollowTheWeekStartPreference() throws Exception {
        Session s = register(uniqueEmail());
        LocalDate sunday = today().minusDays(14).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate monday = sunday.plusDays(1);
        expense(s, "250", category(s, "FOOD_LUNCH"), "transactionDate", sunday.toString());

        String mondayStart = report(s, "weekly?date=" + monday);
        assertThat((String) JsonPath.read(mondayStart, "$.data.from")).isEqualTo(monday.toString());
        assertThat(num(mondayStart, "$.data.summary.expense")).isEqualByComparingTo("0");
        assertThat(num(mondayStart, "$.data.comparison.expense")).isEqualByComparingTo("250");

        mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), s.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("weekStartDay", "SUNDAY")))).andExpect(status().isOk());
        String sundayStart = report(s, "weekly?date=" + monday);
        assertThat((String) JsonPath.read(sundayStart, "$.data.from")).isEqualTo(sunday.toString());
        assertThat((String) JsonPath.read(sundayStart, "$.data.to")).isEqualTo(sunday.plusDays(6).toString());
        assertThat(num(sundayStart, "$.data.summary.expense")).isEqualByComparingTo("250");
        assertThat((String) JsonPath.read(sundayStart, "$.data.highestSpending.date")).isEqualTo(sunday.toString());
        assertThat(num(sundayStart, "$.data.summary.averageDailyExpense")).isEqualByComparingTo("35.71");
    }

    @Test
    void todayFollowsTheUsersTimezone() throws Exception {
        Session s = register(uniqueEmail());
        for (String zone : List.of("Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            mockMvc.perform(authed(patch("/api/v1/users/me/preferences"), s.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("timezone", zone)))).andExpect(status().isOk());
            String r = report(s, "daily");
            assertThat((String) JsonPath.read(r, "$.data.from")).isEqualTo(LocalDate.now(ZoneId.of(zone)).toString());
            assertThat((String) JsonPath.read(r, "$.data.asOf")).isEqualTo(LocalDate.now(ZoneId.of(zone)).toString());
        }
    }

    @Test
    void yearlyAndLongRangesGoMonthByMonth() throws Exception {
        Session s = register(uniqueEmail());
        int year = today().getYear() - 1;
        expense(s, "1200", category(s, "FOOD_LUNCH"), "transactionDate", LocalDate.of(year, 3, 10).toString());
        expense(s, "300", category(s, "FOOD_LUNCH"), "transactionDate", LocalDate.of(year, 7, 2).toString());

        String r = report(s, "yearly?year=" + year);
        assertThat((String) JsonPath.read(r, "$.data.granularity")).isEqualTo("MONTH");
        assertThat((List<?>) JsonPath.read(r, "$.data.series")).hasSize(12);
        assertThat((String) JsonPath.read(r, "$.data.highestSpending.date")).isEqualTo(LocalDate.of(year, 3, 1).toString());
        assertThat(num(r, "$.data.lowestSpending.amount")).isEqualByComparingTo("0");
        assertThat(num(r, "$.data.summary.expense")).isEqualByComparingTo("1500");

        String custom = report(s, "custom?from=" + LocalDate.of(year, 1, 1) + "&to=" + LocalDate.of(year, 6, 30));
        assertThat((String) JsonPath.read(custom, "$.data.granularity")).isEqualTo("MONTH");
        assertThat((List<?>) JsonPath.read(custom, "$.data.series")).hasSize(6);
    }

    @Test
    void customReportsFilterByCategoryAccountAndType() throws Exception {
        Session s = register(uniqueEmail());
        LocalDate d = today().minusDays(3);
        UUID bank = createAccount(s, "Bank", "SAVINGS", "10000");
        expense(s, "300", category(s, "FOOD_LUNCH"), "transactionDate", d.toString());
        expense(s, "200", category(s, "FOOD_DINNER"), "accountId", bank, "transactionDate", d.toString());
        expense(s, "900", category(s, "SHOPPING"), "accountId", bank, "transactionDate", d.toString());
        transfer(s, "1000", bank, defaultAccount(s), "transactionDate", d.toString());
        String range = "custom?from=" + d.minusDays(1) + "&to=" + d;

        String food = report(s, range + "&categoryId=" + category(s, "FOOD"));
        assertThat(num(food, "$.data.summary.expense")).isEqualByComparingTo("500");
        assertThat((String) JsonPath.read(food, "$.data.filters.categoryId")).isEqualTo(category(s, "FOOD").toString());
        assertThat((Object) JsonPath.read(food, "$.data.budgets")).isNull();

        String byBank = report(s, range + "&accountId=" + bank);
        assertThat(num(byBank, "$.data.summary.expense")).isEqualByComparingTo("1100");
        assertThat(num(byBank, "$.data.summary.transfers")).isEqualByComparingTo("1000");
        assertThat((List<?>) JsonPath.read(byBank, "$.data.accounts")).hasSize(1);

        String transfers = report(s, range + "&type=TRANSFER");
        assertThat(num(transfers, "$.data.summary.expense")).isEqualByComparingTo("0");
        assertThat(num(transfers, "$.data.summary.transfers")).isEqualByComparingTo("1000");

        String all = report(s, range);
        assertThat(num(all, "$.data.summary.expense")).isEqualByComparingTo("1400");
        assertThat((String) JsonPath.read(all, "$.data.granularity")).isEqualTo("DAY");
        assertThat((String) JsonPath.read(all, "$.data.comparison.to")).isEqualTo(d.minusDays(2).toString());
    }

    @Test
    void insightsComeFromTheNumbers() throws Exception {
        Session s = register(uniqueEmail());
        YearMonth last = YearMonth.from(today()).minusMonths(1);
        YearMonth before = last.minusMonths(1);
        expense(s, "1000", category(s, "FOOD_LUNCH"), "transactionDate", before.atDay(5).toString());
        income(s, "10000", category(s, "INC_SALARY"), "transactionDate", before.atDay(1).toString());
        expense(s, "1200", category(s, "FOOD_LUNCH"), "transactionDate", last.atDay(5).toString());
        expense(s, "3000", category(s, "SHOPPING"), "transactionDate", last.atDay(6).toString());
        income(s, "10000", category(s, "INC_SALARY"), "transactionDate", last.atDay(1).toString());
        postAuthed("/api/v1/budgets", s, body("scope", "OVERALL", "period", "CUSTOM", "amount", "4000",
                "startDate", last.atDay(1).toString(), "endDate", last.atEndOfMonth().toString())).andExpect(status().isCreated());

        String r = report(s, "monthly?month=" + last);
        List<String> codes = JsonPath.read(r, "$.data.insights[*].code");
        assertThat(codes).contains("SPENDING_CHANGE", "TOP_CATEGORY", "SAVINGS_RATE", "BUDGET_EXCEEDED");
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[?(@.code == 'SPENDING_CHANGE')].message"))
                .containsExactly("Spending is up 320% on last month (₹4,200.00 vs ₹1,000.00).");
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[?(@.code == 'TOP_CATEGORY')].message"))
                .containsExactly("Shopping was your biggest spending category: ₹3,000.00 (71% of spending).");
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[?(@.code == 'SAVINGS_RATE')].message"))
                .containsExactly("Savings rate fell from 90% to 58% compared with last month.");
        assertThat((List<String>) JsonPath.read(r, "$.data.insights[?(@.code == 'BUDGET_EXCEEDED')].tone")).containsExactly("WARNING");

        String empty = report(register(uniqueEmail()), "monthly?month=" + last);
        assertThat((List<String>) JsonPath.read(empty, "$.data.insights[*].code")).containsExactly("NO_ACTIVITY");
    }

    @Test
    void reportsAreValidatedAndIsolated() throws Exception {
        Session a = register(uniqueEmail());
        Session b = register(uniqueEmail());
        expense(a, "500", category(a, "FOOD_LUNCH"));
        assertThat(num(report(b, "daily"), "$.data.summary.expense")).isEqualByComparingTo("0");
        assertThat(num(report(a, "daily"), "$.data.summary.expense")).isEqualByComparingTo("500");

        LocalDate d = today();
        mockMvc.perform(getAuthed("/api/v1/reports/custom?from=" + d + "&to=" + d + "&accountId=" + defaultAccount(a), b.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAuthed("/api/v1/reports/custom?from=" + d + "&to=" + d.minusDays(1), a.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("from"));
        mockMvc.perform(getAuthed("/api/v1/reports/custom?from=" + d.minusYears(3) + "&to=" + d, a.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("to"));
        mockMvc.perform(getAuthed("/api/v1/reports/monthly?month=" + YearMonth.from(d).plusMonths(2), a.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields[0].field").value("month"));
        mockMvc.perform(getAuthed("/api/v1/reports/daily?currency=usd", a.accessToken())).andExpect(status().isBadRequest());
        mockMvc.perform(getAuthed("/api/v1/reports/custom?from=" + d, a.accessToken())).andExpect(status().isBadRequest());
        mockMvc.perform(getAuthed("/api/v1/reports/monthly", null)).andExpect(status().isUnauthorized());
    }
}
