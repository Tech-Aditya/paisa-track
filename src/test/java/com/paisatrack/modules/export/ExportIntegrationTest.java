package com.paisatrack.modules.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.paisatrack.support.FinanceIntegrationTest;

class ExportIntegrationTest extends FinanceIntegrationTest {

    private static final YearMonth LAST_MONTH = YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1);

    private byte[] file(String url, Session s) throws Exception {
        return mockMvc.perform(getAuthed(url, s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Session seeded() throws Exception {
        Session s = register(uniqueEmail());
        UUID bank = createAccount(s, "Bank", "SAVINGS", "10000");
        income(s, "50000", category(s, "INC_SALARY"), "accountId", bank, "transactionDate", LAST_MONTH.atDay(1).toString());
        expense(s, "1234.50", category(s, "FOOD_LUNCH"), "accountId", bank, "transactionDate", LAST_MONTH.atDay(3).toString(),
                "description", "=HYPERLINK(\"http://evil\")", "merchant", "Dhaba, \"Ramu\"");
        expense(s, "800", category(s, "TRANSPORT_CAB"), "accountId", bank, "transactionDate", LAST_MONTH.atDay(10).toString());
        UUID voided = expense(s, "999", category(s, "SHOPPING"), "accountId", bank, "transactionDate", LAST_MONTH.atDay(11).toString());
        postAuthed("/api/v1/transactions/" + voided + "/void", s, java.util.Map.of()).andExpect(status().isOk());
        return s;
    }

    private String range() {
        return "from=" + LAST_MONTH.atDay(1) + "&to=" + LAST_MONTH.atEndOfMonth();
    }

    /** RFC 4180 lines of one CSV text (no line breaks inside cells in these tests). */
    private static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r\n")) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = !quoted;
                } else if (c == ',' && !quoted) {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cell.toString());
            rows.add(cells);
        }
        return rows;
    }

    @Test
    void csvExportListsActiveTransactionsWithTotalsAndNeutralisesFormulas() throws Exception {
        Session s = seeded();
        String text = new String(file("/api/v1/exports/transactions?format=CSV&" + range(), s), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        List<List<String>> rows = csv(text.substring(1));

        int header = rows.indexOf(rows.stream().filter(r -> r.getFirst().equals("Date")).findFirst().orElseThrow());
        List<List<String>> data = rows.subList(header + 1, rows.size()).stream()
                .takeWhile(r -> !r.getFirst().isBlank() && r.getFirst().matches("\\d{4}-\\d{2}-\\d{2}")).toList();
        assertThat(data).hasSize(3); // voided entry left out by default
        assertThat(data.getFirst().get(0)).isEqualTo(LAST_MONTH.atDay(1).toString()); // oldest first
        List<String> lunch = data.get(1);
        assertThat(lunch.get(3)).isEqualTo("1234.50");
        assertThat(lunch).contains("'=HYPERLINK(\"http://evil\")", "Dhaba, \"Ramu\"");

        List<String> totals = rows.stream().filter(r -> r.getFirst().equals("INR")).findFirst().orElseThrow();
        assertThat(new BigDecimal(totals.get(1))).isEqualByComparingTo("50000");
        assertThat(new BigDecimal(totals.get(2))).isEqualByComparingTo("2034.50");
        assertThat(new BigDecimal(totals.get(3))).isEqualByComparingTo("47965.50");
    }

    @Test
    void excelReportMatchesTheJsonReport() throws Exception {
        Session s = seeded();
        String json = getJson("/api/v1/reports/monthly?month=" + LAST_MONTH, s);
        byte[] bytes = file("/api/v1/exports/reports/monthly?format=XLSX&month=" + LAST_MONTH, s);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet summary = wb.getSheet("Summary");
            assertThat(summary).isNotNull();
            assertThat(value(summary, "Income")).isEqualByComparingTo(new BigDecimal(JsonPath.read(json, "$.data.summary.income").toString()));
            assertThat(value(summary, "Expenses")).isEqualByComparingTo(new BigDecimal(JsonPath.read(json, "$.data.summary.expense").toString()));
            assertThat(value(summary, "Expenses")).isEqualByComparingTo("2034.50");
            assertThat(wb.getSheet("Spending by category")).isNotNull();
        }
    }

    @Test
    void excelTransactionsKeepAmountsAsNumbers() throws Exception {
        Session s = seeded();
        byte[] bytes = file("/api/v1/exports/transactions?format=XLSX&includeVoided=true&" + range(), s);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet list = wb.getSheet("Transactions");
            assertThat(list.getLastRowNum()).isEqualTo(4); // header + 4 incl. the voided one
            assertThat(list.getRow(2).getCell(3).getNumericCellValue()).isEqualTo(1234.5);
        }
    }

    @Test
    void pdfExportIsAPdf() throws Exception {
        Session s = seeded();
        byte[] pdf = file("/api/v1/exports/reports/monthly?format=PDF&month=" + LAST_MONTH, s);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        byte[] list = file("/api/v1/exports/transactions?format=PDF&" + range(), s);
        assertThat(new String(list, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void exportsOnlyContainYourOwnData() throws Exception {
        seeded();
        Session other = register(uniqueEmail());
        String text = new String(file("/api/v1/exports/transactions?format=CSV&" + range(), other), StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("HYPERLINK").doesNotContain("1234.50");
    }

    @Test
    void rejectsRangesLongerThanThreeYears() throws Exception {
        Session s = register(uniqueEmail());
        LocalDate to = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        mockMvc.perform(getAuthed("/api/v1/exports/transactions?format=CSV&from=" + to.minusYears(4) + "&to=" + to, s.accessToken()))
                .andExpect(status().isBadRequest());
    }

    private static BigDecimal value(Sheet sheet, String label) {
        for (Row row : sheet) {
            if (row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue())) {
                return BigDecimal.valueOf(row.getCell(1).getNumericCellValue());
            }
        }
        throw new AssertionError("No row " + label);
    }
}
