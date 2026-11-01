package com.paisatrack.modules.imports.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which columns hold what (0-based). Either debit + credit columns, or one amount column
 * (negative = money out, unless a type column says Dr/Cr).
 */
public record ImportMapping(
        @Schema(description = "Row index of the header (0-based); data starts on the next row") Integer headerRow,
        Integer dateColumn,
        Integer descriptionColumn,
        Integer debitColumn,
        Integer creditColumn,
        Integer amountColumn,
        @Schema(description = "Column saying Dr/Cr or Debit/Credit, used with amountColumn") Integer typeColumn,
        @Schema(description = "e.g. dd/MM/yyyy; detected when empty") String dateFormat) {

    public ImportMapping withDateFormat(String format) {
        return new ImportMapping(headerRow, dateColumn, descriptionColumn, debitColumn, creditColumn, amountColumn, typeColumn, format);
    }

    public boolean isEmpty() {
        return dateColumn == null && debitColumn == null && creditColumn == null && amountColumn == null;
    }
}
