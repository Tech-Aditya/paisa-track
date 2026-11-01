package com.paisatrack.modules.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.paisatrack.support.FinanceIntegrationTest;

class ImportIntegrationTest extends FinanceIntegrationTest {

    private static final LocalDate D = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(20);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static String day(int plus) {
        return D.plusDays(plus).format(DMY);
    }

    /** An HDFC-style statement: details above the header, split withdrawal/deposit columns, a footer. */
    private static String statement() {
        return String.join("\n",
                "HDFC BANK Ltd.,,,,,,",
                "Statement of account,,,,,,",
                "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance",
                day(0) + ",UPI-SWIGGY-BANGALORE,0001," + day(0) + ",450.00,,49550.00",
                day(1) + ",UBER INDIA TRIP,0002," + day(1) + ",\"1,200.00\",,48350.00",
                day(2) + ",NEFT CR-ACME CORP SALARY,0003," + day(2) + ",,\"85,000.00\",133350.00",
                day(3) + ",ATM CASH,0004," + day(3) + ",2000.00,,131350.00",
                day(3) + ",ATM CASH,0004," + day(3) + ",2000.00,,129350.00",
                day(4) + ",BROKEN ROW,0005," + day(4) + ",abc,,129350.00",
                ",,,,,,",
                "Opening Balance,Dr Count,Cr Count,,,,",
                "50000.00,4,1,,,,");
    }

    private ResultActions preview(Session s, UUID account, String csv) throws Exception {
        return mockMvc.perform(multipart("/api/v1/imports/preview")
                .file(new MockMultipartFile("file", "Statement Oct.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .param("accountId", account.toString())
                .header("Authorization", "Bearer " + s.accessToken()));
    }

    @Test
    void previewReadsTheStatementFlagsDuplicatesAndSuggestsCategories() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "HDFC", "SAVINGS", "50000");
        // Already entered by hand, a day later than the bank's date: must be flagged.
        UUID cab = expense(s, "1200", category(s, "TRANSPORT_CAB"), "accountId", bank, "transactionDate", D.plusDays(2).toString(), "merchant", "Uber");

        String json = preview(s, bank, statement())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mapping.headerRow").value(2))
                .andExpect(jsonPath("$.data.mapping.dateFormat").value("dd/MM/yyyy"))
                .andExpect(jsonPath("$.data.rowCount").value(6))
                .andExpect(jsonPath("$.data.duplicateCount").value(2))
                .andExpect(jsonPath("$.data.errorCount").value(1))
                .andExpect(jsonPath("$.data.selectedCount").value(3))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(json, "$.data.rows[0].type")).isEqualTo("EXPENSE");
        assertThat(JsonPath.<Double>read(json, "$.data.rows[0].amount")).isEqualTo(450.0);
        assertThat(JsonPath.<String>read(json, "$.data.rows[0].date")).isEqualTo(D.toString());
        assertThat(JsonPath.<String>read(json, "$.data.rows[0].categoryId")).isEqualTo(category(s, "FOOD_RESTAURANTS").toString());
        assertThat(JsonPath.<Boolean>read(json, "$.data.rows[0].selected")).isTrue();

        assertThat(JsonPath.<Boolean>read(json, "$.data.rows[1].selected")).isFalse();
        assertThat(JsonPath.<String>read(json, "$.data.rows[1].duplicateOfTransactionId")).isEqualTo(cab.toString());
        assertThat(JsonPath.<String>read(json, "$.data.rows[1].categoryReason")).isEqualTo("HISTORY");

        assertThat(JsonPath.<String>read(json, "$.data.rows[2].type")).isEqualTo("INCOME");
        assertThat(JsonPath.<String>read(json, "$.data.rows[2].categoryId")).isEqualTo(category(s, "INC_SALARY").toString());

        assertThat(JsonPath.<String>read(json, "$.data.rows[4].duplicateReason")).startsWith("Same as line");
        assertThat(JsonPath.<String>read(json, "$.data.rows[5].error")).startsWith("Can't read the amount");
        assertThat(JsonPath.<Boolean>read(json, "$.data.rows[5].selected")).isFalse();

        // Nothing saved by a preview.
        assertThat(balance(s, bank)).isEqualByComparingTo("48800");
    }

    @Test
    void commitSavesTheTickedRowsOnceEvenWhenRetried() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "HDFC", "SAVINGS", "50000");
        String json = preview(s, bank, statement()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(json, "$.data.rows[?(@.selected == true)]");
        assertThat(rows).hasSize(4);
        UUID misc = category(s, "MISC");
        List<Map<String, Object>> items = rows.stream().map(r -> Map.<String, Object>of(
                "date", r.get("date"), "type", r.get("type"), "amount", r.get("amount"),
                "categoryId", r.get("categoryId") != null ? r.get("categoryId") : misc.toString(),
                "description", r.get("description"))).toList();
        Map<String, Object> body = Map.of("accountId", bank, "fileName", "Statement Oct.csv", "items", items);

        String created = mockMvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/imports/commit"), s.accessToken())
                        .header("Idempotency-Key", "import-test-0001").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imported").value(4))
                .andReturn().getResponse().getContentAsString();
        String batch = JsonPath.read(created, "$.data.batchId");
        // 50000 - 450 - 1200 + 85000 - 2000
        assertThat(balance(s, bank)).isEqualByComparingTo("131350");

        mockMvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/imports/commit"), s.accessToken())
                        .header("Idempotency-Key", "import-test-0001").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.batchId").value(batch));
        assertThat(balance(s, bank)).isEqualByComparingTo("131350");
        mockMvc.perform(getAuthed("/api/v1/imports", s.accessToken())).andExpect(jsonPath("$.data[0].batchId").value(batch));

        // A second preview of the same file now finds everything already recorded.
        preview(s, bank, statement()).andExpect(jsonPath("$.data.selectedCount").value(0));
    }

    @Test
    void aBadRowRollsBackTheWholeImport() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "HDFC", "SAVINGS", "1000");
        UUID food = category(s, "FOOD_LUNCH");
        Map<String, Object> body = Map.of("accountId", bank, "items", List.of(
                Map.of("date", D.toString(), "type", "EXPENSE", "amount", "100", "categoryId", food),
                Map.of("date", D.toString(), "type", "EXPENSE", "amount", "100.123", "categoryId", food)));
        postAuthed("/api/v1/imports/commit", s, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].field").value("items[1].amount"))
                .andExpect(jsonPath("$.error.fields[0].message").value(org.hamcrest.Matchers.endsWith("(row 2)")));
        assertThat(balance(s, bank)).isEqualByComparingTo("1000");
    }

    @Test
    void cannotImportIntoSomeoneElsesAccount() throws Exception {
        Session owner = register(uniqueEmail());
        Session other = register(uniqueEmail());
        UUID bank = createAccount(owner, "HDFC", "SAVINGS", "1000");
        preview(other, bank, statement()).andExpect(status().isNotFound());
        postAuthed("/api/v1/imports/commit", other, Map.of("accountId", bank, "items", List.of(
                Map.of("date", D.toString(), "type", "EXPENSE", "amount", "10", "categoryId", category(other, "MISC")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void explainsFilesItCannotRead() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "HDFC", "SAVINGS", "1000");
        preview(s, bank, "hello,world\nfoo,bar\n").andExpect(status().isUnprocessableContent());
        preview(s, bank, "").andExpect(status().isBadRequest());
        // A single signed amount column works too.
        preview(s, bank, "Txn Date,Description,Amount\n" + day(0) + ",Zomato order,-320\n" + day(1) + ",Refund,320\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.rows[1].type").value("INCOME"));
    }
}
