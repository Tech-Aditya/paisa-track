package com.paisatrack.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class CalendarIntegrationTest extends FinanceIntegrationTest {

    private static final YearMonth M = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1);

    @Test
    void monthShowsDailyTotalsAndEverythingThatWasDue() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "100000");
        expense(s, "300", category(s, "FOOD_LUNCH"), "accountId", bank, "transactionDate", M.atDay(5).toString());
        income(s, "1000", category(s, "INC_FREELANCE"), "accountId", bank, "transactionDate", M.atDay(5).toString());
        expense(s, "200", category(s, "TRANSPORT_CAB"), "accountId", bank, "transactionDate", M.atDay(7).toString());
        UUID gone = expense(s, "999", category(s, "SHOPPING"), "accountId", bank, "transactionDate", M.atDay(7).toString());
        postAuthed("/api/v1/transactions/" + gone + "/void", s, Map.of()).andExpect(status().isOk());

        postAuthed("/api/v1/loans", s, body("name", "Bike loan", "type", "VEHICLE", "principal", "60000", "annualRate", "10",
                "tenureMonths", 12, "startDate", M.atDay(2).toString(), "firstEmiDate", M.atDay(20).toString(), "paymentAccountId", bank))
                .andExpect(status().isCreated());
        postAuthed("/api/v1/lending", s, body("personName", "Ravi", "amount", "1500", "startDate", M.atDay(1).toString(),
                "dueDate", M.atDay(25).toString())).andExpect(status().isCreated());
        Map<String, Object> salary = new HashMap<>(Map.of("name", "Acme salary", "type", "SALARY", "accountId", bank.toString(),
                "expectedAmount", "80000", "expectedDay", 31));
        salary.put("startMonth", M.toString());
        UUID source = createdId(postAuthed("/api/v1/income-sources", s, salary));
        income(s, "80000", null, "incomeSourceId", source, "accountId", bank, "transactionDate", M.atEndOfMonth().toString());

        String json = mockMvc.perform(getAuthed("/api/v1/calendar?month=" + M, s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value(M.toString()))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.income").value(81000.00))
                .andExpect(jsonPath("$.data.expense").value(500.00))
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> day5 = JsonPath.read(json, "$.data.days[?(@.date == '" + M.atDay(5) + "')]");
        assertThat(day5).hasSize(1);
        assertThat(day5.getFirst()).containsEntry("entries", 2);
        assertThat(Double.parseDouble(day5.getFirst().get("expense").toString())).isEqualTo(300.0);
        List<Map<String, Object>> day7 = JsonPath.read(json, "$.data.days[?(@.date == '" + M.atDay(7) + "')]");
        assertThat(Double.parseDouble(day7.getFirst().get("expense").toString())).isEqualTo(200.0); // voided left out

        Map<String, Object> emi = event(json, "EMI");
        assertThat(emi).containsEntry("date", M.atDay(20).toString()).containsEntry("direction", "OUT").containsEntry("status", "OVERDUE");
        Map<String, Object> debt = event(json, "DEBT_DUE");
        assertThat(debt).containsEntry("date", M.atDay(25).toString()).containsEntry("direction", "IN")
                .containsEntry("title", "Ravi repays you").containsEntry("status", "OVERDUE");
        Map<String, Object> pay = event(json, "INCOME_EXPECTED");
        assertThat(pay).containsEntry("date", M.atEndOfMonth().toString()).containsEntry("status", "DONE");
    }

    @Test
    void calendarIsPrivate() throws Exception {
        Session s = register(uniqueEmail());
        expense(s, "300", category(s, "FOOD_LUNCH"), "transactionDate", M.atDay(5).toString());
        Session other = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/calendar?month=" + M, other.accessToken()))
                .andExpect(jsonPath("$.data.days.length()").value(0))
                .andExpect(jsonPath("$.data.events.length()").value(0));
        mockMvc.perform(getAuthed("/api/v1/calendar?month=2026-13", s.accessToken())).andExpect(status().isBadRequest());
    }

    private static Map<String, Object> event(String json, String kind) {
        List<Map<String, Object>> found = JsonPath.read(json, "$.data.events[?(@.kind == '" + kind + "')]");
        assertThat(found).as(kind).hasSize(1);
        return found.getFirst();
    }
}
