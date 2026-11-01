package com.paisatrack.modules.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.paisatrack.modules.imports.dto.ImportMapping;

class StatementParserTest {

    @Test
    void amountsInBankStyles() {
        assertThat(StatementParser.parseAmount("1,23,456.78")).isEqualByComparingTo("123456.78");
        assertThat(StatementParser.parseAmount("₹ 500.00")).isEqualByComparingTo("500");
        assertThat(StatementParser.parseAmount("Rs. 75")).isEqualByComparingTo("75");
        assertThat(StatementParser.parseAmount("500.00 Dr")).isEqualByComparingTo("-500");
        assertThat(StatementParser.parseAmount("500.00 CR")).isEqualByComparingTo("500");
        assertThat(StatementParser.parseAmount("(250)")).isEqualByComparingTo("-250");
        assertThat(StatementParser.parseAmount("-99.5")).isEqualByComparingTo("-99.5");
        assertThat(StatementParser.parseAmount("")).isNull();
        assertThat(StatementParser.parseAmount("-")).isNull();
        assertThatThrownBy(() -> StatementParser.parseAmount("twelve")).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void dateStylesAreDetectedDayFirst() {
        assertThat(StatementParser.dateFormat(List.of("01/10/2026", "13/10/2026"))).isEqualTo("dd/MM/yyyy");
        assertThat(StatementParser.dateFormat(List.of("01/10/26", "31/10/26"))).isEqualTo("dd/MM/yy");
        assertThat(StatementParser.dateFormat(List.of("05-Oct-2026", "06-OCT-2026"))).isEqualTo("dd-MMM-yyyy");
        assertThat(StatementParser.dateFormat(List.of("2026-10-05"))).isEqualTo("yyyy-MM-dd");
        assertThat(StatementParser.dateFormat(List.of("10/13/2026"))).isEqualTo("MM/dd/yyyy");
        assertThat(StatementParser.parseDate("01/10/26", StatementParser.formatter("dd/MM/yy"))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(StatementParser.parseDate("05/10/2026 14:32:10", StatementParser.formatter("dd/MM/yyyy"))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(StatementParser.parseDate("31/02/2026", StatementParser.formatter("dd/MM/yyyy"))).isNull();
        assertThat(StatementParser.looksLikeDate("Opening Balance")).isFalse();
        assertThat(StatementParser.looksLikeDate("05 Oct 2026")).isTrue();
    }

    @Test
    void findsTheHeaderBelowAccountDetails() {
        String text = """
                Account Statement for XXXX1234
                Name: Test User;Branch: MG Road
                Date;Narration;Chq./Ref.No.;Value Dt;Withdrawal Amt.;Deposit Amt.;Closing Balance
                01/10/26;UPI-SWIGGY;123;01/10/26;250.00;;9750.00
                """;
        char d = StatementParser.delimiter(text);
        assertThat(d).isEqualTo(';');
        List<List<String>> rows = StatementParser.rows(text, d);
        ImportMapping m = StatementParser.detect(rows);
        assertThat(m.headerRow()).isEqualTo(2);
        assertThat(m.dateColumn()).isZero();
        assertThat(m.descriptionColumn()).isEqualTo(1);
        assertThat(m.debitColumn()).isEqualTo(4);
        assertThat(m.creditColumn()).isEqualTo(5);
    }

    @Test
    void quotedCellsAndEncodings() {
        List<List<String>> rows = StatementParser.rows("a,\"b, \"\"c\"\"\",d\r\n1,2,3", ',');
        assertThat(rows).containsExactly(List.of("a", "b, \"c\"", "d"), List.of("1", "2", "3"));
        assertThat(StatementParser.decode("﻿Date,Amount".getBytes(StandardCharsets.UTF_8))).isEqualTo("Date,Amount");
        assertThat(StatementParser.decode(new byte[] {'C', 'a', 'f', (byte) 0xE9})).isEqualTo("Café");
    }
}
